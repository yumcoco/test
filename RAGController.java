package lisa.rag.dev.tech.trigger.http;

import jakarta.annotation.Resource;
import jodd.io.FileUtil;
import lisa.rag.dev.tech.api.IRAGService;
import lisa.rag.dev.tech.api.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j

@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")

public class RAGController implements IRAGService {
    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");

        return Response.<List<String>>builder()
                .code("0000")
                .info("IRAGService call successful")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam("ragTag") String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("Upload the knowledge {}...",ragTag);
        for (MultipartFile file : files) {
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            List<Document> documents = documentReader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            pgVectorStore.accept(documentSplitterList);
            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)) {
                elements.add(ragTag);
            }
        }
        log.info("The knowledge {} has been uploaded!",ragTag);
        return Response.<String>builder().code("0000").info("IRAGService call successful").build();
    }

    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam("repoUrl") String repoUrl, @RequestParam("userName") String userName, @RequestParam("token") String token) throws Exception {
        String localPath = "./git-cloned-repo";
        String repoProjectName = extractProjectName(repoUrl);
        log.info("Clone Path:{}", new File(localPath).getAbsolutePath());
        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, token))
                .call();

        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString().toLowerCase();

                List<String> allowedExtensions = Arrays.asList(".txt", ".md", ".html", ".htm", ".xml", ".java", ".py",".js", ".ts", ".json", ".css", ".csv");

                boolean allowed = allowedExtensions.stream().anyMatch(fileName::endsWith);
                if (!allowed) {
                    log.info("Skipping non-text file: {}", fileName);
                    return FileVisitResult.CONTINUE;
                }

                if (Files.size(file) == 0) {
                    log.info("Skipping empty file: {}", fileName);
                    return FileVisitResult.CONTINUE;
                }

                log.info("{}Traverse the path and upload the knowledge base{}", repoProjectName, fileName);
                try {
                    TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                    List<Document> documents = reader.get();

                    documents.removeIf(doc -> {
                        boolean empty = doc.getContent() == null || doc.getContent().isBlank();
                        if (empty) {
                            log.warn("Skipping file with empty content after parsing: {}", fileName);
                        }
                        return empty;
                    });

                    if (documents.isEmpty()) return FileVisitResult.CONTINUE;

                    List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
                    documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    pgVectorStore.accept(documentSplitterList);
                } catch (Exception e) {
                    log.error("Failed to upload knowledge: {} ", fileName, e);
                }

                return FileVisitResult.CONTINUE;
            }



//            @Override
//            public FileVisitResult visitFile(Path file, IOException exc) throws Exception {
//                log.info("Fail to access file:{}-{}", file.toString(), exc.getMessage());
//                return FileVisitResult.CONTINUE;
//            }
        });

        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if(!elements.contains(repoProjectName)){
            elements.add(repoProjectName);
        }
        git.close();
        log.info("Traverse the path and upload the knowledge base Done{}",repoUrl);
        return Response.<String>builder().code("0000").info("IRAGService call successful").build();

    }

    private String extractProjectName(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            return "";
        }
        String[] parts = repoUrl.split("/");
        if (parts.length == 0) {
            return "";
        }
        String projectName = parts[parts.length - 1];
        if (projectName.endsWith(".git")) {
            projectName = projectName.substring(0, projectName.length() - 4);
        }
        return projectName;
    }


}
