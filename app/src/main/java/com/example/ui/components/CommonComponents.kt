package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private object ThumbnailMemoryCache {
    private val cache = object : LruCache<String, Bitmap>(72) {}
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) {
        cache.put(key, bmp)
    }
}

@Composable
fun CategoryBadge(
    category: FileCategory,
    isDirectory: Boolean = false,
    size: Dp = 42.dp
) {
    val (icon: ImageVector, tint: Color) = when {
        isDirectory -> Icons.Rounded.Folder to MaterialTheme.colorScheme.tertiary
        category == FileCategory.APP -> Icons.Rounded.Android to MaterialTheme.colorScheme.secondary
        category == FileCategory.IMAGE -> Icons.Rounded.Image to MaterialTheme.colorScheme.primary
        category == FileCategory.VIDEO -> Icons.Rounded.VideoFile to Color(0xFFFF6B6B)
        category == FileCategory.AUDIO -> Icons.Rounded.AudioFile to Color(0xFFB388FF)
        category == FileCategory.DOCUMENT -> Icons.Rounded.Description to Color(0xFF38BDF8)
        category == FileCategory.ARCHIVE -> Icons.Rounded.FolderZip to MaterialTheme.colorScheme.tertiary
        else -> Icons.Rounded.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = category.name,
            tint = tint,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

@Composable
fun MediaThumbnailBadge(
    uri: Uri?,
    filePath: String?,
    fileName: String,
    category: FileCategory,
    isDirectory: Boolean = false,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val supportsThumb = !isDirectory && (
        category == FileCategory.IMAGE ||
            category == FileCategory.VIDEO ||
            (category == FileCategory.APP && !filePath.isNullOrBlank() && fileName.lowercase().endsWith(".apk"))
        )
    val cacheKey = rememberThumbKey(uri, filePath, fileName)
    val thumbBitmap by produceState<ImageBitmap?>(
        initialValue = if (supportsThumb) ThumbnailMemoryCache.get(cacheKey)?.asImageBitmap() else null,
        cacheKey,
        supportsThumb
    ) {
        if (!supportsThumb) {
            value = null
            return@produceState
        }
        ThumbnailMemoryCache.get(cacheKey)?.let {
            value = it.asImageBitmap()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            loadThumbnailBitmap(context, uri, filePath, fileName, category, maxDim = 256)?.let { bmp ->
                ThumbnailMemoryCache.put(cacheKey, bmp)
                bmp.asImageBitmap()
            }
        }
    }

    if (thumbBitmap != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = thumbBitmap!!,
                contentDescription = fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (category == FileCategory.VIDEO) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    } else {
        Box(modifier = modifier) {
            CategoryBadge(category = category, isDirectory = isDirectory, size = size)
        }
    }
}

@Composable
fun QuickPreviewDialog(
    title: String,
    subtitle: String,
    uri: Uri?,
    filePath: String?,
    category: FileCategory,
    sha256: String = "",
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val previewBmp by produceState<ImageBitmap?>(initialValue = null, uri, filePath, category) {
        if (category == FileCategory.IMAGE || category == FileCategory.VIDEO || category == FileCategory.APP) {
            value = withContext(Dispatchers.IO) {
                loadThumbnailBitmap(context, uri, filePath, title, category, maxDim = 960)?.asImageBitmap()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp!!,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    CategoryBadge(category = category, isDirectory = false, size = 72.dp)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                if (sha256.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.details_sha256_label, sha256),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = {
                    onDismiss()
                    onPrimaryAction()
                }) {
                    Text(primaryActionLabel)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.hub_confirm))
                }
            }
        },
        dismissButton = {
            if (primaryActionLabel != null && onPrimaryAction != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        }
    )
}

@Composable
fun AppIconThumb(
    packageName: String,
    label: String,
    size: Dp = 44.dp
) {
    val context = LocalContext.current
    val cacheKey = "pkg:$packageName"
    val bitmap by produceState<ImageBitmap?>(
        initialValue = ThumbnailMemoryCache.get(cacheKey)?.asImageBitmap(),
        packageName
    ) {
        ThumbnailMemoryCache.get(cacheKey)?.let {
            value = it.asImageBitmap()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = drawableToBitmap(drawable)
                ThumbnailMemoryCache.put(cacheKey, bmp)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = label,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        CategoryBadge(category = FileCategory.APP, size = size)
    }
}

private fun rememberThumbKey(uri: Uri?, filePath: String?, fileName: String): String {
    return "${uri?.toString() ?: ""}|${filePath ?: ""}|$fileName"
}

@Suppress("DEPRECATION")
private fun loadThumbnailBitmap(
    context: Context,
    uri: Uri?,
    filePath: String?,
    fileName: String,
    category: FileCategory,
    maxDim: Int
): Bitmap? {
    return runCatching {
        when (category) {
            FileCategory.IMAGE -> {
                if (maxDim <= 320 && uri != null && uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        return@runCatching context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
                    }
                }
                if (filePath != null && File(filePath).exists()) {
                    decodeSampledBitmapFromFile(filePath, maxDim)
                } else if (uri != null) {
                    decodeSampledBitmapFromUri(context, uri, maxDim)
                } else null
            }
            FileCategory.VIDEO -> {
                if (uri != null && uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        return@runCatching context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
                    }
                }
                if (filePath != null && File(filePath).exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        return@runCatching ThumbnailUtils.createVideoThumbnail(File(filePath), Size(maxDim, maxDim), null)
                    }
                }
                val retriever = MediaMetadataRetriever()
                try {
                    if (filePath != null && File(filePath).exists()) {
                        retriever.setDataSource(filePath)
                    } else if (uri != null) {
                        retriever.setDataSource(context, uri)
                    } else {
                        return null
                    }
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } finally {
                    runCatching { retriever.release() }
                }
            }
            FileCategory.APP -> {
                if (!filePath.isNullOrBlank() && File(filePath).exists() && fileName.lowercase().endsWith(".apk")) {
                    val pkgInfo = context.packageManager.getPackageArchiveInfo(filePath, 0)
                    val appInfo = pkgInfo?.applicationInfo ?: return null
                    appInfo.sourceDir = filePath
                    appInfo.publicSourceDir = filePath
                    val drawable = appInfo.loadIcon(context.packageManager)
                    drawableToBitmap(drawable)
                } else null
            }
            else -> null
        }
    }.getOrNull()
}

private fun decodeSampledBitmapFromFile(path: String, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxDim, maxDim)
    opts.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, opts)
}

private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxDim, maxDim)
    opts.inJustDecodeBounds = false
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
    var inSampleSize = 1
    if (height > reqH || width > reqW) {
        val halfH = height / 2
        val halfW = width / 2
        while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

@Composable
fun EncryptionPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
