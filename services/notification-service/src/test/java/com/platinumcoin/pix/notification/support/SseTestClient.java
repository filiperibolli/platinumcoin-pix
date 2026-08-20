package com.platinumcoin.pix.notification.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A real SSE client for the integration tests: it opens {@code /v1/notifications/stream} over HTTP and
 * reads the frames as they arrive.
 *
 * <p><b>Why a real socket and not MockMvc.</b> The whole subject of this service is a connection that
 * stays open — headers flushed early, bytes arriving later, a client that goes away without saying so.
 * MockMvc's async support completes the exchange and hands back a finished response, which is precisely
 * the thing this service never does; asserting on it would prove the controller returns an
 * {@code SseEmitter} and nothing about streaming.
 *
 * <p>The body is consumed on its own thread into a {@link BlockingQueue}, so a test can say "wait up to
 * N seconds for a line matching this" — waiting on the actual event rather than sleeping and hoping.
 */
public final class SseTestClient implements AutoCloseable {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-test-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> seen = new ArrayList<>();

    private volatile HttpResponse<Stream<String>> response;

    /** Open the stream with the token in an {@code Authorization} header (the curl / fetch shape). */
    public int connectWithHeader(String url, String token) throws IOException, InterruptedException {
        return connect(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token));
    }

    /**
     * Open the stream with the token as a query parameter — the shape a browser's native
     * {@code EventSource} is limited to, since it cannot set request headers.
     */
    public int connectWithQueryParameter(String url, String token)
            throws IOException, InterruptedException {
        return connect(HttpRequest.newBuilder(URI.create(url + "?access_token=" + token)));
    }

    /** Open the stream with no credential at all. */
    public int connectAnonymously(String url) throws IOException, InterruptedException {
        return connect(HttpRequest.newBuilder(URI.create(url)));
    }

    private int connect(HttpRequest.Builder builder) throws IOException, InterruptedException {
        // ofLines() returns as soon as the response HEAD is available and leaves the body streaming —
        // exactly the shape an SSE client needs, and the reason the service writes a comment frame on
        // open: it commits the response so this call returns instead of blocking on an empty stream.
        var request = builder.GET().timeout(Duration.ofSeconds(20)).build();
        this.response = http.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() == 200) {
            reader.submit(() -> response.body().forEach(lines::add));
        }
        return response.statusCode();
    }

    /** The response body of a NON-streaming answer (a 401 problem+json), as one string. */
    public String errorBody() {
        return response.body().reduce("", String::concat);
    }

    public String contentType() {
        return response.headers().firstValue("content-type").orElse("");
    }

    /**
     * Wait up to {@code timeout} for a line containing {@code fragment}.
     *
     * @return the matching line, or {@code null} if none arrived in time
     */
    public String awaitLineContaining(String fragment, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (String line : seen) {
            if (line.contains(fragment)) {
                return line;
            }
        }
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            seen.add(line);
            if (line.contains(fragment)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Drain whatever has arrived so far, waiting {@code quietPeriod} for stragglers first.
     *
     * <p>Used for the negative assertion — "this event did <b>not</b> reach that stream" — which can
     * only be made after giving the wrong frame a fair chance to show up.
     */
    public List<String> drain(Duration quietPeriod) throws InterruptedException {
        long deadline = System.nanoTime() + quietPeriod.toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(50, TimeUnit.MILLISECONDS);
            if (line != null) {
                seen.add(line);
            }
        }
        return List.copyOf(seen);
    }

    @Override
    public void close() {
        reader.shutdownNow();
        http.close();
    }
}
