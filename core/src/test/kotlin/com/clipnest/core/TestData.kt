package com.clipnest.core

/**
 * 测试公共数据：3 条抖音样例（逐字照抄 I:\Download\类似.txt）、噪声清单、ClipItem 构造器。
 * 断言期望值与 Windows 原型 C# SelfTest / 交接清单完全一致。
 */
internal object TestData {

    /** 样例 1：想成为摄影糕手 · 图文作品 */
    const val SAMPLE_1 =
        "4.89 复制打开抖音，看看【想成为摄影糕手的图文作品】下次还要来陪我呀。出镜：@祈枝枝o.o # 王者荣... https://v.douyin.com/depmmgQEbkU/ h@O.kP 03/16 :4pm lcA:/"

    /** 样例 2：圈圈圆圆（求关注版） · 图文作品 */
    const val SAMPLE_2 =
        "5.38 复制打开抖音，看看【圈圈圆圆（求关注版）的图文作品】周一 大肥鱼亲我都不想醒。# deepseek #... https://v.douyin.com/QXrJTCe21i0/ CuF:/ 08/14 b@N.JV :6pm"

    /** 样例 3：红思📸 · 图文作品 */
    const val SAMPLE_3 =
        "0.28 复制打开抖音，看看【红思📸的图文作品】超级可爱的小羽毛球子。# 原神cos # 纳西妲c... https://v.douyin.com/mDJPmVyPUqA/ DUL:/ 08/14 :2pm g@o.da"

    val SAMPLES = listOf(SAMPLE_1, SAMPLE_2, SAMPLE_3)

    /** 与 C# 版交接清单一致的逐字段期望值。 */
    data class Expected(
        val rate: String,
        val author: String,
        val workType: String,
        val title: String,
        val url: String,
        val extra: String,
    )

    val EXPECTED = listOf(
        Expected(
            rate = "4.89",
            author = "想成为摄影糕手",
            workType = "图文作品",
            title = "下次还要来陪我呀。出镜：@祈枝枝o.o # 王者荣...",
            url = "https://v.douyin.com/depmmgQEbkU/",
            extra = "h@O.kP 03/16 :4pm lcA:/",
        ),
        Expected(
            rate = "5.38",
            author = "圈圈圆圆（求关注版）",
            workType = "图文作品",
            title = "周一 大肥鱼亲我都不想醒。# deepseek #...",
            url = "https://v.douyin.com/QXrJTCe21i0/",
            extra = "CuF:/ 08/14 b@N.JV :6pm",
        ),
        Expected(
            rate = "0.28",
            author = "红思📸",
            workType = "图文作品",
            title = "超级可爱的小羽毛球子。# 原神cos # 纳西妲c...",
            url = "https://v.douyin.com/mDJPmVyPUqA/",
            extra = "DUL:/ 08/14 :2pm g@o.da",
        ),
    )

    /** 13 类噪声文本：默认规则下必须 0 命中。 */
    val NOISE = listOf(
        "这是一段普通的中文句子，没有任何链接。",
        "Hello world, this is a normal English sentence with no URL at all.",
        "public static void Main(string[] args) { Console.WriteLine(\"hello\"); }",
        "var url = \"https://github.com/example/repo\"; // 代码里的普通链接，默认不收录",
        "Weibo is a Chinese social media platform founded in 2009.",
        "我的 bilibili 账号叫剪巢测试号，平时也不怎么看 t.cn 短链。",
        "1. 复制这段内容打开抖音，但是既没有口令也没有链接",
        "看看【某人的图文作品】，可惜没有链接",
        "多行文本\n第二行也没有链接\n第三行：youtu.be 看视频",
        "https://v.douyin.com", // 没有短链 code，不算抖音短链
        "t.cn 是新浪的短链服务",
        "这是一段长文本，里面出现 http:// 这个词但没有真正的链接。",
        "https://example.com/plain-link", // generic-link 默认关闭，不收录
    )

    fun item(
        url: String,
        title: String = "标题",
        id: String = "",
        capturedAt: Long = 1_700_000_000_000L,
        starred: Boolean = false,
        platform: String = "douyin",
        platformName: String = "抖音",
    ): ClipItem = ClipItem(
        id = id,
        platform = platform,
        platformName = platformName,
        ruleId = "douyin-share",
        author = "测试作者",
        workType = "图文作品",
        title = title,
        url = url,
        rate = "4.89",
        raw = title,
        capturedAt = capturedAt,
        source = "clipboard",
        starred = starred,
    )
}