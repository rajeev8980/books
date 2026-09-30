package app.box.suggest

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.CachePolicy

class BoxApplication : Application(), SingletonImageLoader.Factory {
    val imageLoader: ImageLoader by lazy { buildImageLoader(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        CacheWiper.wipe(this)
    }
}

private fun buildImageLoader(application: Application): ImageLoader {
    return ImageLoader.Builder(application)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .diskCache(null)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
}
