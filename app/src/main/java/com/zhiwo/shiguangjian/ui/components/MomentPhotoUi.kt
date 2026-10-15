package com.zhiwo.shiguangjian.ui.components

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zhiwo.shiguangjian.data.db.entity.MomentPhotoEntity
import com.zhiwo.shiguangjian.data.photo.MomentPhotoStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次留念照请求：归属日期 + 展示标签（任务名或"手动补拍"） */
data class MomentPhotoRequest(val date: String, val label: String)

/**
 * 由 rememberMomentPhotoHost 创建：两个页面（安排、日记）各自持有，
 * 打开方式只有拍照/选图两路，保存回调由页面自己接（各写各的 ViewModel）。
 */
class MomentPhotoHost internal constructor(
    private val launchCamera: (MomentPhotoRequest) -> Unit,
    private val launchGallery: (MomentPhotoRequest) -> Unit
) {
    fun openCamera(date: String, label: String) = launchCamera(MomentPhotoRequest(date, label))
    fun openGallery(date: String, label: String) = launchGallery(MomentPhotoRequest(date, label))
}

/**
 * 「留念照片」的 UI 侧总入口：系统相机（FileProvider 直写应用私有目录）或相册 Photo Picker。
 * 相机走 ACTION_IMAGE_CAPTURE 不申请 CAMERA 权限；没有可用相机应用时自动退到相册。
 * 取消拍摄留下的 0 字节占位文件在这里就地回收，绝不入库。
 */
@Composable
fun rememberMomentPhotoHost(
    onCameraPhoto: (date: String, label: String, file: File) -> Unit,
    onGalleryPhoto: (date: String, label: String, uri: Uri) -> Unit
): MomentPhotoHost {
    val context = LocalContext.current
    // 挂起中的请求：相机路带占位文件，相册路文件为空
    var pending by remember { mutableStateOf<Pair<MomentPhotoRequest, File?>?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val (req, file) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (saved && file != null && file.exists() && file.length() > 0) {
            onCameraPhoto(req.date, req.label, file)
        } else {
            file?.let { MomentPhotoStore.deleteQuietly(it) }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val (req, file) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        file?.let { MomentPhotoStore.deleteQuietly(it) }
        if (uri != null) onGalleryPhoto(req.date, req.label, uri)
    }

    return remember(context, takePicture, pickImage) {
        MomentPhotoHost(
            launchCamera = { req ->
                val target = MomentPhotoStore.newCameraTarget(context)
                val uri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                } catch (e: Throwable) {
                    MomentPhotoStore.deleteQuietly(target)
                    null
                }
                if (uri == null) {
                    // FileProvider 都拿不到就别硬拍了，退相册
                    pending = req to null
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    return@MomentPhotoHost
                }
                pending = req to target
                try {
                    takePicture.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    MomentPhotoStore.deleteQuietly(target)
                    pending = req to null
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            launchGallery = { req ->
                pending = req to null
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }
}

/** 拍照还是选相册，用户自己挑；点破"会进日记、AI 能看图"两个去处 */
@Composable
fun MomentPhotoChoiceDialog(
    request: MomentPhotoRequest,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("留个纪念 📸") },
        text = {
            Text(
                "为「${request.label.take(24)}」拍张照或挑一张图吧——它会收进 ${request.date} 的日记里；" +
                    "AI 配的是识图模型的话，写日记和每日评价时还能看看它。"
            )
        },
        confirmButton = {
            TextButton(onClick = onCamera) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("拍照")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onGallery) { Text("从相册选") }
                TextButton(onClick = onDismiss) { Text("先不用") }
            }
        }
    )
}

/** 本地照片文件的小尺寸解码：Compose 自带方案，不为此引入图片加载库 */
@Composable
fun rememberFileBitmap(path: String, maxSide: Int = 600): ImageBitmap? {
    var bitmap by remember(path, maxSide) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, maxSide) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            } catch (e: Throwable) {
                null
            }
        }
    }
    return bitmap
}

/** 日记/预览用缩略图：带删除角标，点图本身交给外层放大 */
@Composable
fun MomentPhotoThumb(
    photo: MomentPhotoEntity,
    size: Int = 88,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = rememberFileBitmap(photo.path, maxSide = size * 3)
    Box(modifier = Modifier.size(size.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "留念照片：${photo.label}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "删除照片", tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp))
        }
    }
}
