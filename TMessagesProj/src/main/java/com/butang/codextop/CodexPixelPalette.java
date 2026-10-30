package com.butang.codextop;

import android.util.SparseIntArray;

import org.telegram.ui.ActionBar.Theme;

/** 五套自有样式的纯色表和静态纸面参数；不应用主题、不读写偏好、不参与数据同步。 */
public final class CodexPixelPalette {
    public static final int STYLE_TERMINAL = 1;
    public static final int STYLE_EDITORIAL = 2;
    public static final int STYLE_INDUSTRIAL = 3;
    public static final int STYLE_LIME = 4;
    public static final int STYLE_BLUEPRINT = 6;
    public static final int TEXTURE_NONE = 0;
    public static final int TEXTURE_DOTS = 1;
    public static final int TEXTURE_RULES = 2;
    public static final int TEXTURE_GRID = 3;

    /** 不可变视觉参数。轮廓参数仅供原绘制入口使用，不在这里改变气泡或页面布局。 */
    public static final class Style {
        public final int id;
        public final String name;
        public final boolean dark;
        public final int page, surface, incoming, outgoing, ink, secondary, accentText, accentFill, onAccentFill;
        public final int wallpaper, incomingInk, outgoingInk, incomingSecondary, outgoingSecondary, incomingLink, outgoingLink;
        public final int outlineColor, textureKind, textureColor, textureSecondaryColor;
        public final float outlineWidthDp;
        private final int[] base;
        private final int onErrorFill;

        /** 保存一套只由本类创建的颜色，私有数组不交给调用方，收发文字各自独立。 */
        private Style(int id, String name, boolean dark, int[] base, int onAccentFill, int onErrorFill,
                int wallpaper, int[] chat, int outlineColor, float outlineWidthDp,
                int textureKind, int textureColor, int textureSecondaryColor) {
            this.id = id;
            this.name = name;
            this.dark = dark;
            this.base = base;
            page = base[0]; surface = base[1]; incoming = base[4]; outgoing = base[5];
            ink = base[6]; secondary = base[7]; accentText = base[8]; accentFill = base[9];
            this.onAccentFill = onAccentFill;
            this.onErrorFill = onErrorFill;
            this.wallpaper = wallpaper;
            incomingInk = chat[0]; outgoingInk = chat[1];
            incomingSecondary = chat[2]; outgoingSecondary = chat[3];
            incomingLink = chat[4]; outgoingLink = chat[5];
            this.outlineColor = outlineColor;
            this.outlineWidthDp = outlineWidthDp;
            this.textureKind = textureKind;
            this.textureColor = textureColor;
            this.textureSecondaryColor = textureSecondaryColor;
        }
    }

    // base 顺序：页面/层面/分组/输入，收发底，正文/辅助/强调/填充/按下，完成/危险/错误底，分隔/三种选中/收发选中/底栏。
    private static final Style TERMINAL = new Style(STYLE_TERMINAL, "点阵终端", true, new int[]{
            0xFF0B0E0C, 0xFF131713, 0xFF0B0E0C, 0xFF191D18, 0xFF222720, 0xFFF8F8E3,
            0xFFF3F5E9, 0xFFB3B8AE, 0xFFD6FF70, 0xFFD6FF70, 0xFFBDDF50,
            0xFFA9D561, 0xFFFF8B8B, 0xFFE07B77, 0xFF3C443B, 0x1AFFFFFF, 0x24FFFFFF, 0x24D6FF70,
            0xFF343D2C, 0xFFDEE9BE, 0xFFB3B8AE}, 0xFF151C13, 0xFF151C13, 0xFF0B0E0C,
            new int[]{0xFFF3F5E9, 0xFF151C13, 0xFFC4CBBF, 0xFF454D3D, 0xFFD6FF70, 0xFF354D12},
            0xFF747F68, 0.75f, TEXTURE_DOTS, 0x493D4938, 0x30374331);

    private static final Style EDITORIAL = new Style(STYLE_EDITORIAL, "蓝白出版", false, new int[]{
            0xFFF8F7F1, 0xFFFFFFFF, 0xFFF1F0EB, 0xFFECEDEB, 0xFFFFFEF7, 0xFF1234F5,
            0xFF111418, 0xFF525A63, 0xFF1535D8, 0xFF1639F5, 0xFF0D27B9,
            0xFF1D6D3A, 0xFFB4232A, 0xFFB4232A, 0xFF9DA4A8, 0x10000000, 0x16000000, 0x1A1234F5,
            0xFFE6EBF3, 0xFF0826B5, 0xFF424A53}, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFF8F7F1,
            new int[]{0xFF111418, 0xFFFFFFFF, 0xFF525A63, 0xFFEDF2FF, 0xFF1335CC, 0xFFFFFFFF},
            0xFF19222B, 0.75f, TEXTURE_NONE, 0, 0);

    private static final Style INDUSTRIAL = new Style(STYLE_INDUSTRIAL, "黑橙工业", true, new int[]{
            0xFF171F23, 0xFF1D272B, 0xFF171F23, 0xFF222D32, 0xFF1D272B, 0xFFE4CDAA,
            0xFFF3F6F7, 0xFFB7C0C5, 0xFFFF8A2C, 0xFFFF8A2C, 0xFFEB7619,
            0xFF7AC27E, 0xFFFF9393, 0xFFF17B73, 0xFF465157, 0x1AFFFFFF, 0x24FFFFFF, 0x33FF8A2C,
            0xFF2B3D43, 0xFFD4B588, 0xFFBCC6CB}, 0xFF181F22, 0xFF1B1717, 0xFF171F23,
            new int[]{0xFFF3F6F7, 0xFF191C19, 0xFFB8C7CC, 0xFF40382D, 0xFFFFB270, 0xFF5E3517},
            0xFF5A6A72, 0.75f, TEXTURE_RULES, 0x153D515A, 0x0D3D515A);

    private static final Style LIME = new Style(STYLE_LIME, "白绿色块", false, new int[]{
            0xFFFCFCF6, 0xFFFFFFFF, 0xFFF6F7F1, 0xFFF0F1EC, 0xFFFFFEF8, 0xFFD7FF45,
            0xFF10120B, 0xFF545D50, 0xFF243309, 0xFFD7FF45, 0xFFBFE629,
            0xFF166B36, 0xFFB5232B, 0xFFB5232B, 0xFF8A9483, 0x14000000, 0x1D000000, 0x25243109,
            0xFFEEF2DD, 0xFFB7DC2D, 0xFF121608}, 0xFF121608, 0xFFFFFFFF, 0xFFFCFCF6,
            new int[]{0xFF10120B, 0xFF10120B, 0xFF545D50, 0xFF3A491A, 0xFF264909, 0xFF243D09},
            0xFF10120B, 1.1f, TEXTURE_NONE, 0, 0);

    private static final Style BLUEPRINT = new Style(STYLE_BLUEPRINT, "蓝图控制台", true, new int[]{
            0xFF071F33, 0xFF092A43, 0xFF071F33, 0xFF0C304B, 0xFF092A43, 0xFF6CDEF4,
            0xFFE9F5FF, 0xFF9EC8E3, 0xFF72DEF3, 0xFF6CDEF4, 0xFF45C4DC,
            0xFF54E3B8, 0xFFFF99A8, 0xFFF08193, 0xFF346078, 0x1AFFFFFF, 0x24FFFFFF, 0x3372DEF3,
            0xFF104562, 0xFF4FC3DB, 0xFFA0C6E0}, 0xFF042439, 0xFF2B1119, 0xFF071F33,
            new int[]{0xFFE9F5FF, 0xFF06263A, 0xFFB2D9EF, 0xFF153E52, 0xFF83E9FC, 0xFF0A3952},
            0xFF6AD8EE, 0.75f, TEXTURE_GRID, 0x243D7190, 0x123D7190);

    /** 禁止实例化；样式只通过纯查询方法取得。 */
    private CodexPixelPalette() {}

    /** 保留原浅色签名，缺省使用 02 蓝白出版。 */
    public static SparseIntArray light() {
        return byStyle(STYLE_EDITORIAL);
    }

    /** 保留原深色签名，缺省使用 01 点阵终端。 */
    public static SparseIntArray dark() {
        return byStyle(STYLE_TERMINAL);
    }

    /** 返回五个不可变样式的独立数组；05 已取消，不把空缺 ID 改分配给其他样式。 */
    public static Style[] styles() {
        return new Style[]{TERMINAL, EDITORIAL, INDUSTRIAL, LIME, BLUEPRINT};
    }

    /** 无效或缺失 ID 回到有效浅色 02 或深色 01；有效 ID 保留自己的明暗身份。 */
    public static int resolveStyle(int id, boolean darkFallback) {
        switch (id) {
            case STYLE_TERMINAL: case STYLE_EDITORIAL: case STYLE_INDUSTRIAL: case STYLE_LIME: case STYLE_BLUEPRINT:
                return id;
            default:
                return darkFallback ? STYLE_TERMINAL : STYLE_EDITORIAL;
        }
    }

    /** 默认按浅色回退取得不可变样式，不分配颜色数组。 */
    public static Style metadata(int id) {
        return metadata(id, false);
    }

    /** 按回退侧取得不可变样式；不会将有效暗样式伪装成浅色。 */
    public static Style metadata(int id, boolean darkFallback) {
        switch (resolveStyle(id, darkFallback)) {
            case STYLE_TERMINAL: return TERMINAL;
            case STYLE_INDUSTRIAL: return INDUSTRIAL;
            case STYLE_LIME: return LIME;
            case STYLE_BLUEPRINT: return BLUEPRINT;
            default: return EDITORIAL;
        }
    }

    /** 返回指定样式的新色表，无效 ID 默认回到浅色 02。 */
    public static SparseIntArray byStyle(int id) {
        return byStyle(id, false);
    }

    /** 返回完整的新色表；调用方可修改它，不会改到静态样式或下一次查询。 */
    public static SparseIntArray byStyle(int id, boolean darkFallback) {
        return create(metadata(id, darkFallback));
    }

    /**
     * 用同一组真实 Theme.key 填一套颜色。
     * page 是列表正文底，surface 是顶栏、菜单、对话框和输入栏，wash 是分组底，聊天壁纸单独由 style 提供，field 是搜索与分区。
     * incoming 和 outgoing 是左右气泡；outgoingSelected 只是选中时的平色，不是渐变。
     * accentText 给文字、图标和运行环，accentFill 给独立符号色的填充，accentPressed 是按下的填充。
     * green 保留完成和在线。red 是草稿、删除和红色正文；redFill 只涂错误圆底，上面的叹号由 onErrorFill 单独提供。
     * selector 保持半透明选中。
     * 壁纸和发出气泡的渐变键写 0，合并进已有主题时也不会留下玻璃渐变。
     */
    private static SparseIntArray create(Style style) {
        int[] values = style.base;
        int page = values[0], surface = values[1], wash = values[2], field = values[3];
        int incoming = values[4], outgoing = values[5], ink = values[6], secondary = values[7];
        int accentText = values[8], accentFill = values[9], accentPressed = values[10];
        int green = values[11], red = values[12], redFill = values[13], divider = values[14];
        int selector = values[15], settingsSelector = values[16], chatSelected = values[17];
        int incomingSelected = values[18], outgoingSelected = values[19], tabIdle = values[20];
        SparseIntArray colors = new SparseIntArray(256);
        paint(colors, page, Theme.key_windowBackgroundWhite);
        paint(colors, surface,
                Theme.key_actionBarDefault,
                Theme.key_actionBarDefaultArchived,
                Theme.key_actionBarBrowser,
                Theme.key_actionBarDefaultSubmenuBackground,
                Theme.key_dialogBackground,
                Theme.key_chat_messagePanelBackground,
                Theme.key_chat_topPanelBackground,
                Theme.key_chat_goDownButton,
                Theme.key_undo_background,
                Theme.key_chats_menuBackground);
        paint(colors, wash,
                Theme.key_windowBackgroundGray,
                Theme.key_dialogBackgroundGray);
        paint(colors, field,
                Theme.key_dialogSearchBackground,
                Theme.key_graySection,
                Theme.key_chat_emojiPanelBackground);
        paint(colors, incoming, Theme.key_chat_inBubble);
        paint(colors, outgoing, Theme.key_chat_outBubble);
        // 0 在原壁纸逻辑里表示没有渐变终点，聊天底和发出气泡保持上面的平色。
        paint(colors, 0,
                Theme.key_chat_wallpaper_gradient_to1,
                Theme.key_chat_wallpaper_gradient_to2,
                Theme.key_chat_wallpaper_gradient_to3,
                Theme.key_chat_outBubbleGradient1,
                Theme.key_chat_outBubbleGradient2,
                Theme.key_chat_outBubbleGradient3,
                Theme.key_chat_outBubbleGradientAnimated);
        paint(colors, ink,
                Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_dialogTextBlack,
                Theme.key_chats_name,
                Theme.key_chats_nameArchived,
                Theme.key_chat_messagePanelText,
                Theme.key_actionBarDefaultTitle,
                Theme.key_actionBarDefaultIcon,
                Theme.key_actionBarDefaultSearch,
                Theme.key_actionBarDefaultSubmenuItem,
                Theme.key_actionBarDefaultSubmenuItemIcon,
                Theme.key_windowBackgroundWhiteGrayIcon,
                Theme.key_dialogSearchText,
                Theme.key_dialogIcon,
                Theme.key_undo_infoColor,
                Theme.key_actionBarDefaultArchivedTitle,
                Theme.key_actionBarDefaultArchivedIcon);
        paint(colors, secondary,
                Theme.key_windowBackgroundWhiteGrayText,
                Theme.key_windowBackgroundWhiteGrayText2,
                Theme.key_windowBackgroundWhiteGrayText3,
                Theme.key_chats_message,
                Theme.key_chats_message_threeLines,
                Theme.key_chats_date,
                Theme.key_actionBarDefaultSubtitle,
                Theme.key_actionBarDefaultSearchPlaceholder,
                Theme.key_chat_messagePanelHint,
                Theme.key_chat_messagePanelIcons,
                Theme.key_graySectionText,
                Theme.key_dialogTextGray2,
                Theme.key_dialogSearchHint,
                Theme.key_dialogSearchIcon,
                Theme.key_emptyListPlaceholder,
                Theme.key_actionBarDefaultArchivedSearchPlaceholder,
                // D-83：设置值与分组标题用次要色，每页只在一处用强调色。
                Theme.key_windowBackgroundWhiteValueText,
                Theme.key_windowBackgroundWhiteBlueHeader);
        paint(colors, accentText,
                Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteBlueText4,
                Theme.key_windowBackgroundWhiteLinkText,
                Theme.key_dialogTextBlue,
                Theme.key_dialogTextLink,
                Theme.key_dialogButton,
                Theme.key_chats_actionMessage,
                Theme.key_chats_nameMessage,
                Theme.key_actionBarTabActiveText,
                Theme.key_actionBarTabLine,
                Theme.key_chat_messagePanelCursor,
                Theme.key_chat_messagePanelSend,
                Theme.key_radioBackgroundChecked,
                Theme.key_dialogRadioBackgroundChecked,
                Theme.key_progressCircle,
                Theme.key_glass_tabSelected,
                Theme.key_glass_tabSelectedText,
                Theme.key_chat_topPanelLine,
                Theme.key_chat_replyPanelIcons,
                Theme.key_chat_replyPanelName);
        paint(colors, accentFill,
                Theme.key_chats_unreadCounter,
                Theme.key_chats_actionBackground,
                Theme.key_featuredStickers_addButton,
                Theme.key_switchTrackChecked,
                Theme.key_switch2TrackChecked,
                Theme.key_checkboxSquareBackground,
                Theme.key_dialogRoundCheckBox,
                Theme.key_dialogCheckboxSquareBackground,
                Theme.key_telegram_color,
                Theme.key_dialogFloatingButton,
                Theme.key_windowBackgroundChecked,
                Theme.key_chat_messagePanelVoiceBackground);
        paint(colors, accentPressed,
                Theme.key_chats_actionPressedBackground,
                Theme.key_featuredStickers_addButtonPressed,
                Theme.key_dialogInputFieldActivated,
                Theme.key_windowBackgroundWhiteInputFieldActivated);
        paint(colors, style.onAccentFill,
                Theme.key_chats_unreadCounterText,
                Theme.key_chats_actionIcon,
                Theme.key_featuredStickers_buttonText,
                Theme.key_checkboxSquareCheck,
                Theme.key_dialogRoundCheckBoxCheck,
                Theme.key_dialogCheckboxSquareCheck,
                Theme.key_dialogFloatingIcon,
                Theme.key_windowBackgroundCheckText);
        paint(colors, green,
                Theme.key_windowBackgroundWhiteGreenText,
                Theme.key_chats_onlineCircle,
                Theme.key_chats_sentCheck,
                Theme.key_chats_sentReadCheck);
        paint(colors, red,
                Theme.key_text_RedRegular,
                Theme.key_text_RedBold,
                Theme.key_dialogSwipeRemove,
                Theme.key_chats_draft);
        paint(colors, style.onErrorFill, Theme.key_chats_sentErrorIcon, Theme.key_chat_sentErrorIcon);
        paint(colors, redFill,
                Theme.key_fill_RedNormal,
                Theme.key_chats_sentError,
                Theme.key_chat_sentError);
        paint(colors, divider,
                Theme.key_divider,
                Theme.key_dialogGrayLine,
                Theme.key_actionBarDefaultSubmenuSeparator,
                Theme.key_chat_replyPanelLine);
        paint(colors, selector,
                Theme.key_listSelector,
                Theme.key_chats_tabletSelectedOverlay,
                Theme.key_actionBarDefaultSelector,
                Theme.key_dialogButtonSelector,
                Theme.key_avatar_actionBarSelectorBlue);
        paint(colors, settingsSelector, Theme.key_settings_listSelector);
        paint(colors, chatSelected, Theme.key_chat_selectedBackground);
        paint(colors, incomingSelected, Theme.key_chat_inBubbleSelected);
        paint(colors, outgoingSelected, Theme.key_chat_outBubbleSelected);
        paint(colors, tabIdle, Theme.key_glass_tabUnselected);
        paintChat(colors, style);
        return colors;
    }

    /** 聊天文字按收发气泡分别覆盖；名称、链接、辅助文字和选中态不得回落到全局明暗墨色。 */
    private static void paintChat(SparseIntArray colors, Style style) {
        paint(colors, style.wallpaper, Theme.key_chat_wallpaper);
        paint(colors, style.incomingInk,
                Theme.key_chat_messageTextIn, Theme.key_chat_inReplyNameText, Theme.key_chat_inReplyMessageText,
                Theme.key_chat_inReplyMediaMessageText, Theme.key_chat_inReplyMediaMessageSelectedText,
                Theme.key_chat_inSiteNameText, Theme.key_chat_inFileNameText, Theme.key_chat_inContactNameText,
                Theme.key_chat_inAudioTitleText);
        paint(colors, style.outgoingInk,
                Theme.key_chat_messageTextOut, Theme.key_chat_outReplyNameText, Theme.key_chat_outReplyMessageText,
                Theme.key_chat_outReplyMediaMessageText, Theme.key_chat_outReplyMediaMessageSelectedText,
                Theme.key_chat_outSiteNameText, Theme.key_chat_outFileNameText, Theme.key_chat_outContactNameText,
                Theme.key_chat_outAudioTitleText);
        paint(colors, style.incomingSecondary,
                Theme.key_chat_inTimeText, Theme.key_chat_inTimeSelectedText,
                Theme.key_chat_inFileInfoText, Theme.key_chat_inFileInfoSelectedText,
                Theme.key_chat_inContactPhoneText, Theme.key_chat_inContactPhoneSelectedText,
                Theme.key_chat_inAudioPerformerText, Theme.key_chat_inAudioPerformerSelectedText,
                Theme.key_chat_inAudioDurationText, Theme.key_chat_inAudioDurationSelectedText,
                Theme.key_chat_inVenueInfoText, Theme.key_chat_inVenueInfoSelectedText,
                Theme.key_chat_inAdminText, Theme.key_chat_inAdminSelectedText,
                Theme.key_chat_inViews, Theme.key_chat_inViewsSelected,
                Theme.key_chat_inSentClock, Theme.key_chat_inSentClockSelected);
        paint(colors, style.outgoingSecondary,
                Theme.key_chat_outTimeText, Theme.key_chat_outTimeSelectedText,
                Theme.key_chat_outFileInfoText, Theme.key_chat_outFileInfoSelectedText,
                Theme.key_chat_outContactPhoneText, Theme.key_chat_outContactPhoneSelectedText,
                Theme.key_chat_outAudioPerformerText, Theme.key_chat_outAudioPerformerSelectedText,
                Theme.key_chat_outAudioDurationText, Theme.key_chat_outAudioDurationSelectedText,
                Theme.key_chat_outVenueInfoText, Theme.key_chat_outVenueInfoSelectedText,
                Theme.key_chat_outAdminText, Theme.key_chat_outAdminSelectedText,
                Theme.key_chat_outViews, Theme.key_chat_outViewsSelected,
                Theme.key_chat_outSentClock, Theme.key_chat_outSentClockSelected);
        paint(colors, style.incomingLink,
                Theme.key_chat_messageLinkIn, Theme.key_chat_inForwardedNameText, Theme.key_chat_inViaBotNameText,
                Theme.key_chat_inPsaNameText, Theme.key_chat_inReplyLine, Theme.key_chat_inInstant,
                Theme.key_chat_inInstantSelected, Theme.key_chat_inPreviewInstantText);
        paint(colors, style.outgoingLink,
                Theme.key_chat_messageLinkOut, Theme.key_chat_outForwardedNameText, Theme.key_chat_outViaBotNameText,
                Theme.key_chat_outPsaNameText, Theme.key_chat_outReplyLine, Theme.key_chat_outReplyLine2,
                Theme.key_chat_outInstant, Theme.key_chat_outInstantSelected, Theme.key_chat_outPreviewInstantText,
                Theme.key_chat_outSentCheck, Theme.key_chat_outSentCheckRead,
                Theme.key_chat_outSentCheckSelected, Theme.key_chat_outSentCheckReadSelected);
    }

    /** 把同一个颜色写入一组 Theme.key；所有数组仅在创建色表时分配，不在绘制路径调用。 */
    private static void paint(SparseIntArray colors, int color, int... keys) {
        for (int index = 0; index < keys.length; index++) {
            colors.put(keys[index], color);
        }
    }
}
