# ceff
Cats Effect


### Fibers are not threads

As stated before, fibers are like "light" threads, meaning they can be used in
a similar way than threads to create concurrent code. However, they are not
threads and that means that spawning new fibers does not guarantee that the
action described in the `F` associated to it will be run if there is a shortage
of threads. At the end of the day, if no thread is available that can run, then
the actions in that fiber will be blocked untill some thread is free again.

