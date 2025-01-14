Predicated Async Loader
===
An asynchronous, thread safe, generic loading utility that performs background work and delivers the result to the main thread. 

This class differs from a simple `Executor#execute(Runnable)` + callback in that multiple main thread callers can request work, and be assured that the work will execute once, and that they will all receive the same result. This happens without blocking the main thread, and without synchronization.

The predicate is used to determine if the work is complete. If work is complete, it should return the completed work, otherwise it should return `null`.

For example:
```
Long hash = null;
...
final PredicatedAsyncLoader<Long> loader = 
  new PredicatedAsyncLoader<Long>();
final PredicatedAsyncLoader.Loader<Long>> loader =
  new PredicatedAsyncLoader.Loader<Long>() { computerHash(bigData) };
   final PredicatedAsyncLoader.LoadPredicate<Long> predicate =
     new PredicatedAsyncLoader.LoadPredicate<Long>() { hash };
   final PredicatedAsyncLoader.OnLoadCompleteListener<Long> listener =
     new PredicatedAsyncLoader.OnLoadCompleteListener<Long>() {
       void onLoadSuccess(Long loaded) {
         hash = loaded;
       }
       void onLoadFailed(Exception ex) {*
         Log.e(TAG, "Failed to compute hash", ex);
       }
     };
...
// On main thread...
loader.load(loader, predicate, listener); // First call
loader.load(loader, predicate, listener); // Second call
loader.load(loader, predicate, listener); // Third call
...
```
None of the calls to `loader#load` will block, the computation will only happen once, and the listener will receive the result 3x, with the same result.

Either construct this object such that it's lifecycle matches the process, or call `#shutdow` before it goes out of scope. Failing to call `#shutdown` will leave a background thread running, and if the loader, predicate and listener are not instances of a static class, they will capture and their outer class and prevent it from being garbage collected.

# Use case
The main use case is a complex system where multiple components need an async result, but it isn't feasible for them to otherwise coordinate to obtain it.

Specifically, in AOSP's SystemUI application, there is a case where a potentially expensive view inflation happens behind a "get view" method that accepts a callback: 
- if the view is already inflated, the callback is called immediately on the calling thread.
- otherwise it inflates asynchronoulsy and the callback is later invoked on the main thread.

However, once the "get view" method returns, without invoking the callback immediately, other calls may subsequently invoke "get view". We can use this class to avoid inflating the view again, and ensuring that all the callers see the same inflated result, while keeping the expensive inflation off the main thread.

This is obviously a niche use case. In the above example, the ideal solution is to refactor such that there's a choke point that waits for the view to be inflated before it's accessible to callers. The solution employed here is mitigation that avoids risky refactoring.
