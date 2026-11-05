package com.videostudio.agent;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import com.videostudio.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class StrictAnalysisTest {
    @Test void missingModelCannotProduceAnOfflineReport() {
        var llm=mock(LlmGateway.class);
        var agent=new AnalysisAgent(llm,new PromptLibrary(),mock(HybridRetriever.class),new AgentProperties());
        assertThrows(IllegalStateException.class,()->agent.run(new AnalysisAgent.Request(1,"goal",AnalysisMode.GENERAL,List.of(),List.of(),List.of(),"1:00"),message -> {}));
    }
    @Test void emptyExecutorOutputCannotProduceATemplateReport() {
        var llm=mock(LlmGateway.class); when(llm.enabled()).thenReturn(true);
        when(llm.chat(anyList(),anyDouble(),anyInt(),anyString())).thenAnswer(call -> {
            String purpose=call.getArgument(3);
            String text=switch(purpose) {
                case "retrieval-plan" -> "{\"semanticQuery\":\"goal\",\"keywords\":[\"goal\"]}";
                case "plan" -> "{\"understoodGoal\":\"goal\",\"tasks\":[\"analyze\"]}";
                default -> "invalid response";
            };
            return new LlmGateway.ChatResult(text,0,0,"test");
        });
        var retriever=mock(HybridRetriever.class); when(retriever.search(anyLong(),anyString(),anyList(),anyList(),anyList())).thenReturn(List.of());
        var agent=new AnalysisAgent(llm,new PromptLibrary(),retriever,new AgentProperties());
        assertThrows(IllegalStateException.class,()->agent.run(new AnalysisAgent.Request(1,"goal",AnalysisMode.GENERAL,List.of(),List.of(),List.of(),"1:00"),message -> {}));
    }
}
