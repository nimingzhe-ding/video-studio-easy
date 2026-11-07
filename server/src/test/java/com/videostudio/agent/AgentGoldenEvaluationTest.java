package com.videostudio.agent;

import com.videostudio.common.Json;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.*;
import com.videostudio.llm.LlmGateway;
import com.videostudio.retrieval.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Fixed labelled inputs exercise production ranking and validation without calling billable models. */
class AgentGoldenEvaluationTest {
    record Context(String id,List<ContextChunk> chunks,List<TranscriptSegment> transcripts,List<VisualEvidence> visuals) {}
    record RetrievalCase(String id,String context,String query,Map<Integer,Double> semanticScores,
                         List<Integer> relevantChunks,int topK) {}
    record QualityCase(String id,String context,AgentModels.Draft draft,List<String> requiredSections,boolean expectedPassed) {}
    record Thresholds(double recallAtK,double meanReciprocalRank,double verdictAccuracy) {}
    record Suite(int schemaVersion,Thresholds thresholds,List<Context> contexts,
                 List<RetrievalCase> retrievalCases,List<QualityCase> qualityCases) {}

    @Test void labelledCasesMeetRankingAndEvidenceThresholds() throws Exception {
        Suite suite;
        try(var input=getClass().getResourceAsStream("/evaluation/golden-cases.json")) {
            assertNotNull(input);
            suite=Json.mapper().readValue(input,Suite.class);
        }
        assertEquals(1,suite.schemaVersion());
        assertFalse(suite.retrievalCases().isEmpty()); assertFalse(suite.qualityCases().isEmpty());
        var contexts=new HashMap<String,Context>();
        for(var context:suite.contexts()) assertNull(contexts.put(context.id(),context),"duplicate context id");
        var ids=new HashSet<String>();
        var retrievalResults=new ArrayList<Map<String,Object>>();
        double recallSum=0,reciprocalSum=0;
        for(var sample:suite.retrievalCases()) {
            assertTrue(ids.add(sample.id()),"duplicate evaluation id");
            var context=Objects.requireNonNull(contexts.get(sample.context()),"unknown context");
            assertFalse(sample.relevantChunks().isEmpty());
            var gateway=mock(LlmGateway.class); var vectors=mock(VectorStore.class);
            when(gateway.embed(anyString())).thenReturn(List.of(1d,0d));
            when(vectors.semanticScores(anyLong(),anyList(),anyList())).thenReturn(sample.semanticScores());
            var properties=new AgentProperties(); properties.setTopKChunks(sample.topK());
            var hits=new HybridRetriever(gateway,properties,vectors,null,false)
                .search(1,sample.query(),context.chunks(),context.transcripts(),context.visuals());
            var ranked=hits.stream().map(hit->hit.chunk().chunkIndex()).toList();
            double recall=(double)sample.relevantChunks().stream().filter(ranked::contains).count()/sample.relevantChunks().size();
            double reciprocal=0;
            for(int i=0;i<ranked.size();i++) if(sample.relevantChunks().contains(ranked.get(i))) { reciprocal=1d/(i+1); break; }
            recallSum+=recall; reciprocalSum+=reciprocal;
            retrievalResults.add(Map.of("id",sample.id(),"ranked",ranked,"relevant",sample.relevantChunks(),
                "recallAtK",recall,"reciprocalRank",reciprocal));
        }
        var qualityResults=new ArrayList<Map<String,Object>>();
        int correct=0;
        for(var sample:suite.qualityCases()) {
            assertTrue(ids.add(sample.id()),"duplicate evaluation id");
            var context=Objects.requireNonNull(contexts.get(sample.context()),"unknown context");
            var outcome=EvidenceValidator.verify(sample.draft(),context.transcripts(),context.visuals(),sample.requiredSections());
            if(outcome.passed()==sample.expectedPassed()) correct++;
            qualityResults.add(Map.of("id",sample.id(),"expectedPassed",sample.expectedPassed(),"actualPassed",outcome.passed(),
                "problems",outcome.problems()));
        }
        double recall=recallSum/retrievalResults.size(),mrr=reciprocalSum/retrievalResults.size();
        double accuracy=(double)correct/qualityResults.size();
        var report=Map.of("schemaVersion",1,"scope","Deterministic retrieval and evidence regression; no live model evaluation",
            "retrievalCases",retrievalResults,"qualityCases",qualityResults,
            "metrics",Map.of("recallAtK",recall,"meanReciprocalRank",mrr,"verdictAccuracy",accuracy),"thresholds",suite.thresholds());
        Path output=Path.of("target/evaluation"); Files.createDirectories(output);
        Files.writeString(output.resolve("report.json"),Json.writePretty(report));
        Files.writeString(output.resolve("report.md"),"# Agent regression evaluation\n\n"
            +"Fixed labelled cases; no live models or middleware.\n\n"
            +"| Metric | Actual | Threshold |\n|---|---:|---:|\n"
            +"| Recall@K | "+recall+" | "+suite.thresholds().recallAtK()+" |\n"
            +"| MRR | "+mrr+" | "+suite.thresholds().meanReciprocalRank()+" |\n"
            +"| Evidence verdict accuracy | "+accuracy+" | "+suite.thresholds().verdictAccuracy()+" |\n\n"
            +retrievalResults.size()+" retrieval cases, "+qualityResults.size()+" evidence cases. See report.json for individual results.\n");
        assertAll(
            ()->assertTrue(recall>=suite.thresholds().recallAtK(),"retrieval recall below threshold; see target/evaluation/report.json"),
            ()->assertTrue(mrr>=suite.thresholds().meanReciprocalRank(),"retrieval ranking below threshold; see target/evaluation/report.json"),
            ()->assertEquals(1d,accuracy,"evidence acceptance/rejection changed; see target/evaluation/report.json"),
            ()->assertTrue(accuracy>=suite.thresholds().verdictAccuracy()));
    }
}
