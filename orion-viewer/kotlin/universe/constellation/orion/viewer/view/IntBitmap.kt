package universe.constellation.orion.viewer.view

import universe.constellation.orion.viewer.geom.Dimension
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect


public class IntBitmap(val width: Int, val height: Int, val offset: Int, val cache : Cache) {

    val endOffset = offset + intCounts()

    val array = cache.array

    var isDead = false
        private set

    public fun intCounts(): Int = width * height

    public fun Canvas.drawBitmap(x: Float, y: Float, width: Int, height: Int, paint: Paint ) {
        println("${this@IntBitmap.width} $width")
        this.drawBitmap(cache.array, offset, this@IntBitmap.width, 0, 0, width, height, true, paint);
    }

    public fun Canvas.drawBitmap(r: Rect, paint: Paint) {
        drawBitmap(r.left.toFloat(), r.top.toFloat(), r.width(), r.height(), paint);
    }

    fun destroy() {
        println("destroy bitmap")
        isDead = true
    }
}


public class Cache private(val screenSize : Dimension) {

    val array: IntArray = IntArray(screenSize.width * screenSize.height * 5)
    val intervals = linkedListOf<IntBitmap>()

    val totalSize = array.size

    //TODO wrong cycling
    public fun createBitmap(requestWidth: Int, requestHeight: Int): IntBitmap {
        val width = calcDim(requestWidth, screenSize.width)
        val height = calcDim(requestHeight, screenSize.height)

        var offset = findFreeOffset(height * width)
        if (offset == -1) {
            val mutableIterator = intervals.iterator()
            for (i in mutableIterator) {
                if (i.isDead) {
                    mutableIterator.remove()
                }
            }
            offset = findFreeOffset(height * width)
        }

        if (offset == -1) {
            throw RuntimeException("not enough memory: total memory " + array.size)
        }

        val bitmap = IntBitmap(width, height, offset, this)
        val insertAtBegining = (intervals.first?.offset ?: 0) > offset

        val insertIndex = if (insertAtBegining) 0 else intervals.size
        println("Creating new bitmap with index $insertIndex offset $offset bitmap ${bitmap.width}x${bitmap.height}")
        intervals.add(insertIndex, bitmap)

        return bitmap;
    }

    fun calcDim(request: Int, screenDim: Int): Int {
        return Math.min(request, (1.1 * screenDim).toInt())
    }

    private fun findFreeOffset(needBytes: Int): Int {
        val firstFreeAtEnd = intervals.last?.endOffset ?: 0
        val firstOccupied = intervals.first?.offset ?: 0

        return if (firstFreeAtEnd + needBytes < totalSize) {
            firstFreeAtEnd
        } else if (needBytes < firstOccupied) {
            firstOccupied - needBytes
        } else {
            -1
        }
    }

    class object {

        var cache: Cache? = null;

        fun create(dim: Dimension): Cache {
            if (cache == null) {
                cache = Cache(dim)
            }
            return cache!!
        }
    }
}
