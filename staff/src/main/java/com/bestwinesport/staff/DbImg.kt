package com.bestwinesport.staff

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.map.Mapper
import coil.request.Options
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.File

/** An image stored inside Firebase (imageData/<id>), referenced as "dbimg:<id>". Works without the Railway image backend. */
data class DbImg(val id: String)

object DbImages {
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Returns the image bytes (cached on the phone after the first download). */
    suspend fun bytes(ctx: Context, id: String): ByteArray? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "dbimg").apply { mkdirs() }
        val f = File(dir, safe(id))
        if (f.exists() && f.length() > 0) return@withContext f.readBytes()
        try {
            val v = FirebaseDatabase.getInstance().getReference("imageData").child(id).get().await().value?.toString() ?: return@withContext null
            val data = android.util.Base64.decode(v.substringAfter("base64,", v), android.util.Base64.DEFAULT)
            f.writeBytes(data)
            data
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }
}

class DbImageMapper : Mapper<String, DbImg> {
    override fun map(data: String, options: Options): DbImg? = if (data.startsWith("dbimg:")) DbImg(data.removePrefix("dbimg:")) else null
}

class DbImageKeyer : Keyer<DbImg> {
    override fun key(data: DbImg, options: Options): String = "dbimg:" + data.id
}

class DbImageFetcher(private val data: DbImg, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = DbImages.bytes(options.context, data.id) ?: return null
        return SourceResult(ImageSource(Buffer().write(bytes), options.context), null, DataSource.NETWORK)
    }
    class Factory : Fetcher.Factory<DbImg> {
        override fun create(data: DbImg, options: Options, imageLoader: ImageLoader): Fetcher = DbImageFetcher(data, options)
    }
}
