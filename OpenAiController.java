package lisa.rag.dev.tech.trigger.http;

import jakarta.annotation.Resource;
import lisa.rag.dev.tech.api.IAiService;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/openai/")

public class OpenAiController implements IAiService {

        @Resource
        private OpenAiChatClient chatClient;
        @Resource
        private PgVectorStore pgVectorStore;


        @RequestMapping(value = "generate", method = RequestMethod.GET)
        @Override
        public ChatResponse generate(@RequestParam("model") String model, @RequestParam("message") String message) {

            return chatClient.call(new Prompt(message,
                    OpenAiChatOptions.builder()
                            .withModel(model)
                            .build()
            ));
        }


        @RequestMapping(value = "generate_stream", method = RequestMethod.GET)
        @Override
        public Flux<ChatResponse> generateStream(@RequestParam("model") String model, @RequestParam("message") String message) {

            return chatClient.stream(new Prompt(message,
                    OpenAiChatOptions.builder()
                            .withModel(model)
                            .build()
            ));
        }

    @RequestMapping(value = "generate_stream_rag", method = RequestMethod.GET)
    @Override
    public Flux<ChatResponse> generateStreamRag(@RequestParam("model") String model, @RequestParam("ragTag") String ragTag, @RequestParam("message") String message) {
        String SYSTEM_PROMPT = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in English!
                DOCUMENTS:
                    {documents}
                """;

        SearchRequest request = SearchRequest.query(message)
                .withTopK(5)
                .withFilterExpression("knowledge == '" + ragTag + "'");

        List<Document> documents = pgVectorStore.similaritySearch(request);
        String documentsCollectors = documents.stream().map(Document::getContent).collect(Collectors.joining());

        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("documents", documentsCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(message));
        messages.add(ragMessage);

        return chatClient.stream(new Prompt(messages, OllamaOptions.create().withModel(model)));

    }
}

