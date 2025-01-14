package org.jtb.predicated_async_loader;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Perform work on a background thread, and deliver the result to the main thread.
 * <p/>
 * Where this class differs from a simple {@link java.util.concurrent.Executor#execute(Runnable)}
 * this a runnable is that multiple main thread callers can request work, and be assured
 * that the work will execute once, and that they will all receive the same result. This
 * happens without ever blocking the main thread, and without synchronization.
 * <p/>
 * The predicate is used to determine if the work is complete. If the work is
 * complete, it should return the completed work, otherwise it should return null.
 * <p/>
 * For example:
 * <pre>
 *   Long hash = null;
 *   ...
 *   final PredicatedAsyncLoader<Long> loader = new PredicatedAsyncLoader<Long>();
 *
 *   final PredicatedAsyncLoader.Loader<Long>> loader =
 *     new PredicatedAsyncLoader.Loader<Long>() { computerHash(bigData) };
 *   final PredicatedAsyncLoader.LoadPredicate<Long> predicate =
 *     new PredicatedAsyncLoader.LoadPredicate<Long>() { hash };
 *   final PredicatedAsyncLoader.OnLoadCompleteListener<Long> listener =
 *     new PredicatedAsyncLoader.OnLoadCompleteListener<Long>() {
 *       void onLoadSuccess(Long loaded) {
 *         hash = loaded;
 *       }
 *       void onLoadFailed(Exception ex) {*
 *         Log.e(TAG, "Failed to compute hash", ex);
 *       }
 *     };
 *   ...
 *
 *   // On main thread...
 *   loader.load(loader, predicate, listener); // First call
 *   loader.load(loader, predicate, listener); // Second call
 *   loader.load(loader, predicate, listener); // Third call
 *   ...
 * </pre>
 * None of the calls to {@code loader.load()} will block, the computation will only happen once,
 * and the listener will receive the result 3x, with the same result.
 * <p/>
 * Either construct this object such that it's lifecycle matches the process, or call
 * {@link #shutdown()} before it goes out of scope. Failing to call {@link #shutdown()} will
 * leave a background thread running, and if the loader, predicate and listener are not
 * instances of a static class, they will capture and their outer class and prevent it from
 * being garbage collected.
 */
public final class PredicatedAsyncLoader<T> {
  private static final String TAG = "PredicatedAsyncLoader";
  private static final int MAX_QUEUE_SIZE = 16;

  private final Handler handler;
  private final LoaderThread<T> loaderThread;

  public interface LoadPredicate<T> {
    @Nullable T getLoaded();
  }

  public interface OnLoadCompleteListener<T> {
    void onLoadSuccess(@Nullable T loaded);
    void onLoadFailed(@NonNull Exception ex);
  }

  public PredicatedAsyncLoader() {
    handler = new Handler(Looper.getMainLooper());
    loaderThread = new LoaderThread<>();
    loaderThread.start();
  }

  @UiThread
  public void load(
      @NonNull Callable<T> loader,
      @NonNull LoadPredicate<T> predicate,
      @NonNull OnLoadCompleteListener<T> listener
  ) {
    try {
      T existing = predicate.getLoaded();
      if (existing != null) {
        listener.onLoadSuccess(existing);
        return;
      }

      LoadRequest<T> request = LoadRequest.create(
          loader,
          predicate,
          listener,
          handler
      );

      if (!loaderThread.enqueue(request)) {
        // This is immediately caught below
        throw new IllegalStateException("Inflation queue full (" + MAX_QUEUE_SIZE + " requests)");
      }
    } catch (Exception e) {
      listener.onLoadFailed(e);
    }
  }

  public void shutdown() {
    loaderThread.interrupt();
  }

  private static class LoaderThread<T> extends Thread {

    private final BlockingQueue<LoadRequest<T>> queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

    private LoaderThread() {
      setName(TAG);
    }

    @Override
    public void run() {
      while (!isInterrupted()) {
        runInner();
      }
    }

    public void runInner() {
      LoadRequest<T> request;
      try {
        request = queue.take();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }

      final AtomicReference<T> predicateResult = new AtomicReference<>();
      final AtomicReference<Exception> predicateError = new AtomicReference<>();

      try {
        runSynchronouslyOnHandler(request.handler, () -> {
          predicateResult.set(request.predicate.getLoaded());
          return null;
        });
      } catch (Exception e) {
        predicateError.set(e);
      }

      if (predicateError.get() != null) {
        postOnLoadFailed(request, predicateError.get());
        return;
      }

      final T predicated = predicateResult.get();
      if (predicated != null) {
        postOnLoadSuccess(request, predicated);
        return;
      }

      try {
        final T loaded = request.loader.call();
        postOnLoadSuccess(request, loaded);
      } catch (Exception ex) {
        postOnLoadFailed(request, ex);
      }
    }

    private void postOnLoadSuccess(
        LoadRequest<T> request,
        @Nullable T loaded
    ) {
      request.handler.post(() -> {
        request.callback.onLoadSuccess(loaded);
      });
    }

    private void postOnLoadFailed(
        @NonNull LoadRequest<T> request,
        @NonNull Exception ex) {
      request.handler.post(() -> {
        request.callback.onLoadFailed(ex);
      });
    }

    public boolean enqueue(LoadRequest<T> request) {
      return queue.offer(request);
    }
  }

  private static final class LoadRequest<T> {
    private final Callable<T> loader;
    private final OnLoadCompleteListener<T> callback;
    private final LoadPredicate<T> predicate;
    private final Handler handler;

    private LoadRequest(
        @NonNull Callable<T> loader,
        @NonNull OnLoadCompleteListener<T> callback,
        @NonNull LoadPredicate<T> predicate,
        @NonNull Handler handler
    ) {
      this.loader = loader;
      this.callback = callback;
      this.predicate = predicate;
      this.handler = handler;
    }

    public static <T> LoadRequest<T> create(
        @NonNull Callable<T> loader,
        @NonNull LoadPredicate<T> predicate,
        @NonNull OnLoadCompleteListener<T> callback,
        @NonNull Handler handler
    ) {
      return new LoadRequest<>(loader, callback, predicate, handler);
    }
  }

  private static <T> T runSynchronouslyOnHandler(Handler handler, Callable<T> callable) throws Exception {
    FutureTask<T> futureTask = new FutureTask<>(callable);
    handler.post(futureTask);
    return futureTask.get();
  }
}