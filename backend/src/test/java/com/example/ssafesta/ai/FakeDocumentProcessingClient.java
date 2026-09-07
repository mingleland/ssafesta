package com.example.ssafesta.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Records what Spring tried to send instead of sending it.
 *
 * <p>Same arrangement as {@code FakeObjectStorage}: a plain class in the interface's package under
 * {@code src/test}, arranged through public setters rather than a mocking framework, with a
 * {@link #reset()} the tests call from {@code @BeforeEach}.
 */
public class FakeDocumentProcessingClient implements DocumentProcessingClient {

    private final List<ProcessingRequest> received = new ArrayList<>();

    /** When set, every call throws it. The delegation path has to survive an unreachable FastAPI. */
    private volatile RuntimeException failure;

    @Override
    public synchronized void startProcessing(ProcessingRequest request) {
        RuntimeException arranged = failure;
        if (arranged != null) {
            // Recorded before throwing: a test asserting a retry needs to count the attempts that
            // failed, not only the ones that landed.
            received.add(request);
            throw arranged;
        }
        received.add(request);
    }

    /** Every call in order, failed ones included. */
    public synchronized List<ProcessingRequest> received() {
        return List.copyOf(received);
    }

    public synchronized ProcessingRequest onlyRequest() {
        if (received.size() != 1) {
            throw new IllegalStateException("위임 호출이 1건이어야 하는데 " + received.size() + "건입니다.");
        }
        return received.get(0);
    }

    public void failWith(RuntimeException arranged) {
        this.failure = arranged;
    }

    public synchronized void reset() {
        received.clear();
        failure = null;
    }
}
