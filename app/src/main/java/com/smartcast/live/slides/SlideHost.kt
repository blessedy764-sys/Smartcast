In the file that contains `class SlideHost` (the one you labelled "Slide Session"), make TWO edits:

1) Add this line under `@Volatile private var current: List<ByteArray> = emptyList()`:

    @Volatile var repeatMs: Long = 5000L // eco mode stretches this

2) In `private fun schedule(...)` change:

    val delay = if (sent < 3) 250L else 5000L
to:
    val delay = if (sent < 3) 250L else repeatMs

