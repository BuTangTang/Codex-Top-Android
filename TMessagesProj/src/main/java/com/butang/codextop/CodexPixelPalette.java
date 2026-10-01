package com.butang.codextop;

import android.util.SparseIntArray;

import org.telegram.ui.ActionBar.Theme;

/**
 * 像素双主题的纯颜色表。
 * 浅色是白底和淡蓝层面，深色是石墨基底和层面；只返回颜色，不应用主题、不写偏好、不提供额度。
 */
public final class CodexPixelPalette {
    /** 填充按钮、角标和勾上的符号色。两套主题都用白色，保证压在蓝色或红色填充上仍可读。 */
    private static final int ON_FILL = 0xFFFFFFFF;

    /** 禁止实例化；调用方只取 light 或 dark。 */
    private CodexPixelPalette() {}

    /**
     * 返回浅色蓝白表。
     * 页面保持白色，分组底和壁纸用淡蓝。文字、图标和运行环用蓝；白字填充用同一蓝色家族里仍能托住白字的一档。
     * 每次新建数组，本类不保留也不会写入主题。
     */
    public static SparseIntArray light() {
        return create(
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFEAF4FC, 0xFFEAF4FC, 0xFFFFFFFF, 0xFFE4F2FC,
                0xFF1A1D21, 0xFF5E686F, 0xFF1868A8, 0xFF1565C0, 0xFF1257A8,
                0xFF1C7A30, 0xFFC62828, 0xFFC62828,
                0xFFD0DEEC, 0x0F000000, 0x1D000010, 0x241868A8,
                0xFFE7F3FC, 0xFFDCEEF8, 0xFF1A1D21);
    }

    /**
     * 返回深色石墨表。
     * 页面和壁纸用 #141D28，顶栏、对话框、输入栏和收到的气泡用 #202834；发出的气泡只比层面再平推一档，便于区分左右。
     * 蓝色重点在深底上用亮蓝，白字填充仍用同一档深蓝。每次新建数组，不应用、不保存。
     */
    public static SparseIntArray dark() {
        return create(
                0xFF141D28, 0xFF202834, 0xFF141D28, 0xFF202834, 0xFF202834, 0xFF243447,
                0xFFF4F7FA, 0xFF9AA8B6, 0xFF64B5EF, 0xFF1565C0, 0xFF1257A8,
                0xFF61BD67, 0xFFF08A90, 0xFFC62828,
                0xFF314052, 0x1AFFFFFF, 0x24FFFFFF, 0x3364B5EF,
                0xFF2A3C4C, 0xFF2B3E52, 0xFF9AA8B6);
    }

    /**
     * 用同一组真实 Theme.key 填一套颜色。
     * page 是列表正文底，surface 是顶栏、菜单、对话框和输入栏，wash 是分组底和聊天壁纸，field 是搜索与分区。
     * incoming 和 outgoing 是左右气泡；outgoingSelected 只是选中时的平色，不是渐变。
     * accentText 给文字、图标和运行环，accentFill 给白字填充，accentPressed 是按下的填充。
     * green 保留完成和在线。red 是草稿、删除和红色正文；redFill 只涂错误圆底，上面的叹号仍是白色。
     * selector 保持半透明选中。
     * 壁纸和发出气泡的渐变键写 0，合并进已有主题时也不会留下玻璃渐变。
     */
    private static SparseIntArray create(
            int page, int surface, int wash, int field, int incoming, int outgoing,
            int ink, int secondary, int accentText, int accentFill, int accentPressed,
            int green, int red, int redFill,
            int divider, int selector, int settingsSelector, int chatSelected,
            int incomingSelected, int outgoingSelected, int tabIdle) {
        SparseIntArray colors = new SparseIntArray(140);
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
                Theme.key_dialogBackgroundGray,
                Theme.key_chat_wallpaper);
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
                Theme.key_chat_messageTextIn,
                Theme.key_chat_messageTextOut,
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
                Theme.key_chat_inTimeText,
                Theme.key_chat_outTimeText,
                Theme.key_dialogSearchHint,
                Theme.key_dialogSearchIcon,
                Theme.key_emptyListPlaceholder,
                Theme.key_actionBarDefaultArchivedSearchPlaceholder);
        paint(colors, accentText,
                Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteBlueText4,
                Theme.key_windowBackgroundWhiteValueText,
                Theme.key_windowBackgroundWhiteLinkText,
                Theme.key_windowBackgroundWhiteBlueHeader,
                Theme.key_dialogTextBlue,
                Theme.key_dialogTextLink,
                Theme.key_dialogButton,
                Theme.key_chats_actionMessage,
                Theme.key_chats_nameMessage,
                Theme.key_actionBarTabActiveText,
                Theme.key_actionBarTabLine,
                Theme.key_chat_messagePanelCursor,
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
                Theme.key_chat_messagePanelSend,
                Theme.key_switchTrackChecked,
                Theme.key_switch2TrackChecked,
                Theme.key_checkboxSquareBackground,
                Theme.key_dialogRoundCheckBox,
                Theme.key_dialogCheckboxSquareBackground,
                Theme.key_radioBackgroundChecked,
                Theme.key_dialogRadioBackgroundChecked,
                Theme.key_telegram_color,
                Theme.key_dialogFloatingButton,
                Theme.key_windowBackgroundChecked,
                Theme.key_chat_messagePanelVoiceBackground);
        paint(colors, accentPressed,
                Theme.key_chats_actionPressedBackground,
                Theme.key_featuredStickers_addButtonPressed,
                Theme.key_dialogInputFieldActivated,
                Theme.key_windowBackgroundWhiteInputFieldActivated);
        paint(colors, ON_FILL,
                Theme.key_chats_unreadCounterText,
                Theme.key_featuredStickers_buttonText,
                Theme.key_chats_sentErrorIcon,
                Theme.key_chat_sentErrorIcon,
                Theme.key_checkboxSquareCheck,
                Theme.key_dialogRoundCheckBoxCheck,
                Theme.key_dialogCheckboxSquareCheck,
                Theme.key_dialogFloatingIcon,
                Theme.key_windowBackgroundCheckText);
        paint(colors, green,
                Theme.key_windowBackgroundWhiteGreenText,
                Theme.key_chats_onlineCircle,
                Theme.key_chats_sentCheck,
                Theme.key_chats_sentReadCheck,
                Theme.key_chat_outSentCheck,
                Theme.key_chat_outSentCheckRead,
                Theme.key_chat_outSentCheckSelected,
                Theme.key_chat_outSentCheckReadSelected);
        paint(colors, red,
                Theme.key_text_RedRegular,
                Theme.key_text_RedBold,
                Theme.key_dialogSwipeRemove,
                Theme.key_chats_draft);
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
        return colors;
    }

    /** 把同一个颜色写入一组 Theme.key。键必须各不相同，否则后写的值会盖住先写的值。 */
    private static void paint(SparseIntArray colors, int color, int... keys) {
        for (int index = 0; index < keys.length; index++) {
            colors.put(keys[index], color);
        }
    }
}
