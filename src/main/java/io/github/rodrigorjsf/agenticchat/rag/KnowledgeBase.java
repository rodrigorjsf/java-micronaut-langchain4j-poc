package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micronaut.context.annotation.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The assistant's own documentation, embedded and searchable.
 *
 * <p>The corpus is deliberately about <em>this assistant</em> and the data it
 * serves, not about the world. Facts about the world come from tools, where they
 * are current and authoritative; a retrieval corpus of world facts would go stale
 * and compete with the tools for the model's trust. What tools cannot answer is
 * "what can you do", "why did you say not found", "what is a CEP actually" — and
 * those are exactly the questions this holds.
 *
 * <p>Ingestion happens once at startup and lives in memory. At this corpus size
 * that is a few dozen segments and well under a megabyte, so a vector database
 * would be a container and an operational surface bought for nothing. The store
 * is rebuilt on every boot, which also means the corpus can never drift from the
 * files in the repository.
 *
 * <p>{@code @Context} rather than {@code @Singleton} so this runs eagerly. The
 * quantized ONNX model takes about 5.7 s to load on this machine, and paying that
 * on the first user request instead of at boot would hand one unlucky user the
 * entire cold start.
 */
@Context
public class KnowledgeBase {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String DIRECTORY = "knowledge";

    /**
     * ~600 characters with 100 of overlap. Small enough that a retrieved segment is
     * mostly signal, large enough to keep a heading with the paragraph under it.
     * The overlap exists so a fact split across a boundary survives in one of the
     * two halves.
     */
    private static final int SEGMENT_CHARS = 600;
    private static final int OVERLAP_CHARS = 100;

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel embeddingModel;
    private final int segmentCount;

    public KnowledgeBase(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;

        long started = System.nanoTime();
        var segments = new ArrayList<TextSegment>();
        for (Document document : loadDocuments()) {
            segments.addAll(DocumentSplitters.recursive(SEGMENT_CHARS, OVERLAP_CHARS).split(document));
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("No knowledge documents found on the classpath under " + DIRECTORY);
        }

        // One batch call rather than one per segment: the in-process model amortises
        // its own setup across the batch, and this is the only ingestion of the run.
        var embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);
        this.segmentCount = segments.size();

        LOG.info("Knowledge base ready: {} segments from {} documents in {} ms",
                segmentCount, loadDocuments().size(), (System.nanoTime() - started) / 1_000_000);
    }

    public EmbeddingStore<TextSegment> store() {
        return store;
    }

    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    public int segmentCount() {
        return segmentCount;
    }

    /**
     * Reads every {@code .md} under {@code resources/knowledge/}. The file name
     * becomes {@code source} metadata so a retrieved segment can be attributed back
     * to a document the user could actually be pointed at.
     */
    private static List<Document> loadDocuments() {
        var url = Thread.currentThread().getContextClassLoader().getResource(DIRECTORY);
        if (url == null) {
            return List.of();
        }
        try {
            Path directory = resolve(url.toURI());
            try (Stream<Path> files = Files.list(directory)) {
                return files
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .map(KnowledgeBase::toDocument)
                        .toList();
            }
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException("Could not read the knowledge directory",
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /** Works whether the classes are on disk or packaged inside the fat jar. */
    private static Path resolve(java.net.URI uri) throws IOException {
        if (!"jar".equals(uri.getScheme())) {
            return Path.of(uri);
        }
        String withinJar = uri.getSchemeSpecificPart();
        int separator = withinJar.indexOf("!/");
        try {
            return FileSystems.newFileSystem(uri, Map.of()).getPath(withinJar.substring(separator + 1));
        } catch (java.nio.file.FileSystemAlreadyExistsException alreadyOpen) {
            return FileSystems.getFileSystem(uri).getPath(withinJar.substring(separator + 1));
        }
    }

    private static Document toDocument(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String fileName = path.getFileName().toString();
            return Document.from(text, Metadata.from(Map.of(
                    "source", fileName,
                    "title", titleOf(text, fileName))));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read knowledge document " + path, e);
        }
    }

    private static String titleOf(String markdown, String fallback) {
        return markdown.lines()
                .filter(line -> line.startsWith("# "))
                .findFirst()
                .map(line -> line.substring(2).strip())
                .orElse(fallback);
    }
}
