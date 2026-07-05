package com.einkreader.core;

/**
 * 全局常量定义
 */
public class Constants {
    /** 默认字体大小（SP 单位），适配 7.8 寸 300 PPI 墨水屏 */
    public static final int DEFAULT_FONT_SIZE_SP = 30;
    
    /** 章节标题行首最小匹配字符数 */
    public static final int MIN_CHAPTER_TITLE_PREFIX_CHARS = 5;
    
    /** 首行缩进最小可用宽度缓冲（像素） */
    public static final int MIN_INDENT_AVAILABLE_WIDTH_PX = 10;
    
    /** 页码字体大小（SP 单位） */
    public static final int PAGE_NUMBER_TEXT_SIZE_SP = 14;
    
    /** Nook GlowLight Plus 7.8 屏幕分辨率宽度（像素） */
    public static final int NOOK_GL_SCREEN_WIDTH_PX = 1404;
    
    /** Nook GlowLight Plus 7.8 屏幕分辨率高度（像素） */
    public static final int NOOK_GL_SCREEN_HEIGHT_PX = 1872;
    
    /** Nook GlowLight Plus 7.8 屏幕像素密度（PPI） */
    public static final int NOOK_GL_PPI = 300;
    
    private Constants() {
        // 防止实例化
    }
}