package com.butang.codextop;

import android.graphics.BitmapFactory;
import java.io.File;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;

/** 将附件映射到原版图片或文件消息，不访问网络、生成缩略图或维护传输状态。 */
public final class AttachmentMessages {
    /** 已接收描述只保存元数据，本地已校验文件由现有缓存owner传入。 */
    public static void apply(TLRPC.TL_message message, DesktopAttachment attachment, File cachedFile) {
        if (message.params == null) message.params = new HashMap<>();
        message.params.remove("codexPendingFile");
        message.params.remove("codexSelectedFile");
        message.params.put("codexAttachment", attachment.toJson().toString());
        media(message, attachment.name, attachment.kind, attachment.sizeBytes, cachedFile);
    }

    /** 待发保留原件描述与手机路径，不把未上传原件冒充电脑上的附件。 */
    public static void applyPending(TLRPC.TL_message message, DesktopAttachment.Pending pending) {
        if (message.params == null) message.params = new HashMap<>();
        message.params.remove("codexAttachment");
        message.params.remove("codexSelectedFile");
        message.params.put("codexPendingFile", pending.toJson().toString());
        media(message, pending.name, pending.kind, pending.sizeBytes, new File(pending.localPath));
    }

    /** 暂存前只显示所选本地文件，真实摘要与持久元数据由stage完成后的Pending提供。 */
    public static void applySelected(TLRPC.TL_message message, File local, String name, String kind) {
        media(message, name, kind, null, local);
    }

    /** 重开恢复未暂存选择，仍使用原失败气泡；不存在的来源不伪装成本地已就绪文件。 */
    public static void applySelected(TLRPC.TL_message message, OutboxStore.Selection selected) {
        if (message.params == null) message.params = new HashMap<>();
        message.params.remove("codexAttachment"); message.params.remove("codexPendingFile");
        message.params.put("codexSelectedFile", selected.localPath == null ? "" : selected.localPath);
        message.params.put("codexSelectedName", selected.name); message.params.put("codexSelectedKind", selected.kind);
        if (selected.mimeType == null) message.params.remove("codexSelectedMime");
        else message.params.put("codexSelectedMime", selected.mimeType);
        media(message, selected.name, selected.kind, null, selected.localPath == null ? null : new File(selected.localPath));
    }

    /** 原消息只引用真实存在的本地文件；电脑路径与虚拟显示编号均不能用作attachPath。 */
    private static void media(TLRPC.TL_message message, String name, String kind, Long size, File cachedFile) {
        File local = cachedFile != null && cachedFile.isFile() ? cachedFile : null;
        message.attachPath = local == null ? "" : local.getAbsolutePath();
        message.flags |= 512;
        long displayId = message.dialog_id ^ ((long) message.id << 32);
        if (displayId == 0) displayId = 1;
        long bytes = size == null ? local == null ? 0 : local.length() : size;
        if ("image".equals(kind)) {
            TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
            media.flags = 1;
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = displayId; photo.date = message.date; photo.file_reference = new byte[0];
            TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
            photoSize.type = "x"; photoSize.w = 320; photoSize.h = 240;
            photoSize.size = (int) Math.min(Integer.MAX_VALUE, bytes);
            if (local != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(local.getAbsolutePath(), options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    photoSize.w = options.outWidth; photoSize.h = options.outHeight;
                }
            }
            TLRPC.TL_fileLocationUnavailable location = new TLRPC.TL_fileLocationUnavailable();
            location.volume_id = Integer.MIN_VALUE;
            location.local_id = ((int) (displayId ^ (displayId >>> 32))) | Integer.MIN_VALUE;
            location.file_reference = new byte[0];
            photoSize.location = location;
            photo.sizes.add(photoSize); media.photo = photo; message.media = media;
        } else {
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
            media.flags = 1;
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.id = displayId; document.date = message.date; document.file_reference = new byte[0];
            document.mime_type = "application/octet-stream"; document.size = bytes;
            TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
            filename.file_name = name; document.attributes.add(filename);
            media.document = document; message.media = media;
        }
    }
}
