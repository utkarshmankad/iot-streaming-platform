package iot.platform.gateway.ingest;

import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class BoundedResponse implements BodySubscriber<byte[]> {
  private final BodySubscriber<byte[]> target = BodySubscribers.ofByteArray();
  private final int limit;
  private Flow.Subscription subscription;
  private long received;

  BoundedResponse(int limit) {
    this.limit = limit;
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return target.getBody();
  }

  @Override
  public void onSubscribe(Flow.Subscription upstream) {
    subscription = upstream;
    target.onSubscribe(upstream);
  }

  @Override
  public void onNext(List<ByteBuffer> chunks) {
    for (var chunk : chunks) {
      received += chunk.remaining();
    }
    if (received > limit) {
      subscription.cancel();
      target.onError(new IOException("Backend batch response exceeds its size limit"));
      return;
    }
    target.onNext(chunks);
  }

  @Override
  public void onError(Throwable failure) {
    target.onError(failure);
  }

  @Override
  public void onComplete() {
    target.onComplete();
  }
}
