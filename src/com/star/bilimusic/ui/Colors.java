package com.star.bilimusic.ui;

/**
 * 界面配色集中定义。
 *
 * 之前 23 种颜色散落在两个 Activity 里，其中「0xFF888888 / 0xFF999999 / 0xFF777777」
 * 是三种几乎分不出的灰，改主题要改十几处。这里统一成有明确语义的几档。
 *
 * 层次从上到下由亮到暗，保证对比度递进：
 *   主文字 → 次级文字 → 弱提示 → 禁用
 */
public final class Colors {

    private Colors() {}

    // ---------------------------------------------------------------- 主题

    /** 主题色（B 站粉）。强调按钮、选中态、进度条。 */
    public static final int ACCENT = 0xFFFB7299;
    /** 主题色的半透明版本，用于标签底、描边。 */
    public static final int ACCENT_DIM = 0x22FB7299;
    public static final int ACCENT_STROKE = 0x55FB7299;

    /** 危险/错误：删除、失败提示。 */
    public static final int DANGER = 0xFFFF6B6B;
    /** 成功：登录成功等。 */
    public static final int SUCCESS = 0xFF8BC34A;

    // ---------------------------------------------------------------- 背景

    /** 页面底色。 */
    public static final int BG = 0xFF121218;
    /** 列表奇数行。 */
    public static final int BG_ROW_ALT = 0xFF15151C;

    /** 批量选择模式下被选中行的背景（淡粉，和强调色同源）。 */
    public static final int BG_SELECTED = 0xFF3A2230;
    /** 卡片 / 迷你条 / 弹窗按钮底。 */
    public static final int BG_CARD = 0xFF1E1E28;
    /** 分隔线、描边。 */
    public static final int DIVIDER = 0xFF2E2E3A;
    /** 封面占位。 */
    public static final int PLACEHOLDER = 0xFF2A2A35;

    // ---------------------------------------------------------------- 文字

    /** 主文字（标题、歌名）。 */
    public static final int TEXT_PRIMARY = 0xFFEDEDED;
    /** 次级文字（UP 主、副标题）。 */
    public static final int TEXT_SECONDARY = 0xFF888888;
    /** 弱提示（未激活的按钮、说明文字）。 */
    public static final int TEXT_HINT = 0xFFB0B0BE;
    /** 禁用态。 */
    public static final int TEXT_DISABLED = 0xFF5A5A66;
    /** 极小字（署名、时间）。 */
    public static final int TEXT_FAINT = 0xFF4A4A55;
}
