package com.moxu.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.context.ContextItem;
import com.moxu.context.ContextSelectEngine;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.Fact;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.SceneBrief;
import com.moxu.nlp.AdaptiveHintProfile;
import com.moxu.nlp.AdaptiveHintResolver;
import com.moxu.nlp.HanlpLexiconSupport;
import com.moxu.storage.CardStorage;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 档案员 Agent - 生成场景简报和管理设定
 */
@Slf4j
@Component
public class ArchivistAgent extends BaseAgent {

    private static final int MAX_CANON_FACTS = 5;
    private static final int CHAPTER_SUMMARY_MAX_ATTEMPTS = 3;
    private static final int CHAPTER_SUMMARY_SOURCE_LIMIT = 3000;
    private static final int CHAPTER_SUMMARY_SOURCE_HEAD_CHARS = 1800;
    private static final int CHAPTER_SUMMARY_SOURCE_TAIL_CHARS = 1200;
    private static final int CANON_UPDATE_SOURCE_HEAD_CHARS = 2200;
    private static final int CANON_UPDATE_SOURCE_TAIL_CHARS = 1800;
    private static final int OPEN_LOOP_TAIL_CHARS = 1400;
    private static final int CHARACTER_STATE_SOURCE_HEAD_CHARS = 2200;
    private static final int CHARACTER_STATE_SOURCE_TAIL_CHARS = 1800;
    private static final int RAW_SUMMARY_MIN_CHARS = 80;
    private static final double RAW_SUMMARY_PREFIX_RATIO = 0.88d;
    private static final Pattern SIMPLE_RELATION_FACT_RE = Pattern.compile(
            "^(.{1,12})是(.{1,16})的(母亲|父亲|儿子|女儿|哥哥|姐姐|弟弟|妹妹|妻子|丈夫|恋人|朋友|同学|老师|学生|主人|仆人)[。.!?？]*$"
    );
    private static final Pattern SIMPLE_RELATION_FACT_RE_EN = Pattern.compile(
            "^(.+?) is (.+?)'?s? (mother|father|son|daughter|brother|sister|wife|husband|lover|friend|classmate|teacher|student|master|servant)[.!?]*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WORLD_TOKEN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9]{2,12}(?:宗|门|派|阁|宫|殿|城|国|界|山|谷|岛|洲|域|族|诀|功|术|法|塔|府|院|堂|会|盟|监狱|学院|组织|公司|集团))"
    );
    private static final Pattern SPEECH_NAME = Pattern.compile(
            "([\\p{IsHan}]{2,3})(?:轻声|低声|冷声|寒声|哑声|沉声|缓缓)?(?=道|说道|问道|开口|说|问|笑|哭|想)"
    );
    private static final Pattern LEADING_HAN_BLOCK = Pattern.compile("^[\\p{IsHan}]{2,8}");
    private static final Pattern TEMPORAL_SEQUENCE = Pattern.compile(
            "^第?[0-9零〇一二两三四五六七八九十百千万]+(?:天|日|周|月|年|次)$"
    );
    private static final List<String> FACT_DENSITY_HINTS = List.of(
            "规则", "禁忌", "代价", "必须", "不允许", "禁止", "不得", "无法", "不能",
            "承诺", "约定", "秘密", "交易", "交换", "契约",
            "决定", "命令", "发现", "确认", "意识到", "试图", "企图", "暴露", "背叛", "威胁",
            "受伤", "内伤", "病", "死亡", "失踪", "获得", "失去", "遗失", "受困", "隔绝", "见底", "匮乏", "成为", "沦为",
            "本质", "机制", "秘术", "禁术", "本源", "神魂", "修为", "天赋",
            "控制", "掌握", "剥离", "吞噬", "掠夺", "搜罗", "狩猎", "血脉", "印记",
            "人工痕迹", "遗迹", "入口", "异常", "反应", "证据", "记录", "线索", "疗伤", "资源", "工具", "传承"
    );
    private static final List<String> FACT_MECHANISM_HINTS = List.of(
            "可以", "能够", "会", "必须", "不得", "禁止", "不能", "无法",
            "通过", "借此", "意味着", "而非", "不是", "用来", "用于", "触发", "显现", "影响", "关联", "限制", "反应"
    );
    private static final List<String> FACT_DURABLE_HINTS = List.of(
            "规则", "禁忌", "代价", "本质", "机制", "秘术", "禁术", "本源", "修为", "天赋",
            "神魂印记", "契约", "命令", "决定", "意识到", "确认", "获得", "失去", "成为", "沦为",
            "剥离", "吞噬", "掠夺", "控制", "用于", "用来", "试图", "企图", "搜罗", "狩猎", "血脉", "种子",
            "遗失", "受困", "隔绝", "见底", "匮乏", "内伤", "疗伤", "人工痕迹", "遗迹", "入口", "拖擦声", "异响", "青光",
            "证据", "记录", "线索", "异常", "反应", "传承", "身份", "关系", "势力", "工具"
    );
    private static final List<String> FACT_LOW_VALUE_HINTS = List.of(
            "流光", "角落", "香气", "发丝", "衣摆", "绒毯", "帷幕", "裙摆", "眼尾",
            "睫毛", "唇瓣", "肌肤", "喘息", "呻吟", "酥麻", "潮红", "赤足", "柔软", "快感", "冲击"
    );
    private static final List<String> FACT_SCENE_OVERRIDE_HINTS = List.of(
            "规则", "禁忌", "代价", "本质", "机制", "秘术", "禁术", "本源", "神魂",
            "修为", "天赋", "剥离", "吞噬", "掠夺", "控制", "命令", "决定", "意识到", "成为", "不再", "失去", "获得", "死亡",
            "遗失", "受困", "隔绝", "见底", "匮乏", "内伤", "疗伤", "人工痕迹", "遗迹", "入口", "异常", "反应", "传承"
    );
    private static final List<String> FACT_MOMENTARY_ACTION_HINTS = List.of(
            "软倒", "翻白", "涌入", "射出", "抬手", "踩在", "喘", "发颤", "跌坐", "跪伏", "扬起", "滑落"
    );
    private static final List<String> FACT_WORKLOG_HINTS = List.of(
            "完成任务", "清通", "回堵", "巡查确认", "来回确认", "路过弟子", "谈论", "喝令", "站住", "回去集结"
    );
    private static final List<String> FACT_UNCERTAIN_HINTS = List.of(
            "认为", "以为", "似乎", "仿佛", "好像", "觉得", "疑似", "像是"
    );
    private static final List<String> FACT_DISCOVERY_HINTS = List.of(
            "入口", "遗迹", "线索", "证据", "记录", "异常", "反应", "纹路", "图谱", "机关", "传承"
    );
    private static final Pattern LOW_VALUE_VISUAL_FACT_RE = Pattern.compile(
            ".*(一道.+流光|一缕.+流光|最阴暗的角落|缩在.+角落|醉生梦死).*"
    );
    private static final Pattern FACT_UNNAMED_GENERIC_ACTOR_RE = Pattern.compile(
            "^(?:一名|一位|一个|两名|几名|有人|数名|一伙).*(?:弟子|男子|女子|人|壮实弟子|路过弟子).*$"
    );
    private static final Set<String> CHARACTER_STOPWORDS = Set.of(
            "本章", "此时", "这里", "一个", "一种", "自己", "他们", "她们", "我们", "你们", "因为", "所以", "然后", "但是",
            "如果", "已经", "没有", "不是", "不能", "不会", "再次", "终于", "时候", "东西", "事情", "地方", "声音", "目光",
            "身体", "神魂", "丹田", "子宫", "肉身", "魔胎", "小腹", "世界", "章节", "故事", "读者", "作者", "问题", "答案",
            "所有", "弟子", "众人", "主角", "反派", "双方", "局势", "气息", "神色", "一行", "其中", "有人", "无人",
            "一边", "两人", "随后", "最终", "面对", "认为", "于是", "之后", "结果", "开始", "继续", "决定", "发现", "表示",
            "提出", "强调", "担心", "感到", "原本", "只能", "试图", "准备", "由于", "为了", "顺手", "独自", "后来"
    );
    private static final Set<String> CHARACTER_HARD_NEGATIVE_TOKENS = Set.of(
            "家里", "家中", "屋里", "屋内", "房里", "房中", "门外", "门口", "门内", "院里", "院中",
            "路边", "路上", "床边", "桌边", "窗边", "身后", "眼前", "眼底", "手里", "怀里", "心里"
    );
    private static final Set<Character> CHARACTER_NAME_PREFIX_CHARS = Set.of('老', '小', '阿');
    private static final List<String> STRONG_CHARACTER_TITLE_SUFFIXES = List.of(
            "局长", "警司", "警官", "警员", "队长", "主任", "老板", "总裁", "总监", "经理", "老师", "伯父", "伯母",
            "伯", "叔", "姨", "哥", "姐", "总", "董", "局"
    );
    private static final List<String> WEAK_CHARACTER_TITLE_SUFFIXES = List.of(
            "小姐", "先生", "女士", "太太", "夫人", "少爷", "姑娘", "少主"
    );
    private static final Set<String> TEMPORAL_NARRATIVE_TOKENS = Set.of(
            "今天", "昨日", "昨天", "明天", "后天", "当天", "那天", "这天", "当日", "次日", "翌日", "隔日",
            "今夜", "昨夜", "当夜", "夜里", "深夜", "半夜", "凌晨", "清晨", "早晨", "清早", "早上",
            "上午", "中午", "午后", "下午", "傍晚", "晚上", "白天", "白日", "白昼", "夜晚"
    );
    private static final Set<Character> CHARACTER_DISCOURSE_ENDING_CHARS = Set.of(
            '的', '地', '得', '了', '着', '过', '在', '把', '将', '被', '向', '从', '对', '与', '和', '跟',
            '并', '且', '但', '却', '而', '又', '还', '都', '也', '就', '便', '再', '正', '于', '是', '有',
            '沉', '轻', '低', '缓', '看', '站', '走', '跑', '冲', '扑', '抱', '想', '说', '问', '笑', '哭',
            '没', '未',
            '抿', '抬', '摇', '点', '皱', '望', '盯'
    );
    private static final Set<Character> CHARACTER_LOCATION_ENDING_CHARS = Set.of(
            '里', '中', '内', '外', '边', '上', '下', '前', '后'
    );
    private static final Set<String> CHARACTER_POSITIVE_RIGHT_HINTS = Set.of(
            "说", "问", "答", "笑", "哭", "想", "看", "望", "盯", "叫", "喊", "骂",
            "开口", "低声", "轻声", "沉声", "抬头", "低头", "点头", "摇头", "伸手", "抬手",
            "走", "跑", "冲", "扑", "抱", "亲", "吻", "坐", "站", "看着", "望着"
    );
    private static final Set<String> LEADING_CHARACTER_ACTION_HINTS = Set.of(
            "回", "回到", "把", "坐", "站", "看", "听", "问", "说", "笑", "哭", "想", "抬", "低",
            "拿", "伸", "盘", "引", "揉", "停", "闭", "睁", "下", "起", "没", "未", "只", "便", "先"
    );
    private static final Set<String> CHARACTER_SUBJECT_PREFIX_HINTS = Set.of(
            "在", "把", "将", "正", "就", "便", "又", "也", "先", "再", "还", "已经", "仍", "忽然", "立刻", "直接"
    );
    private static final Set<String> CHARACTER_SUBJECT_ACTION_HINTS = Set.of(
            "带", "说", "问", "看", "盯", "笑", "哭", "走", "跑", "站", "坐", "推", "拿", "递",
            "抬", "低", "回", "转", "伸", "抱", "跟", "进", "出", "按", "拉", "拽", "扶"
    );
    private static final Set<String> CHARACTER_NEGATIVE_RIGHT_HINTS = Set.of(
            "收拾", "收拾好了", "那边", "这边", "里面", "外面", "附近",
            "不大不小", "不小", "很大", "太大", "太小"
    );
    private static final Set<String> CHARACTER_CONCEPT_LEFT_HINTS = Set.of(
            "施展", "使用", "修炼", "催动", "发动", "运转", "掌握", "学会", "获得", "这种", "此种"
    );
    private static final Set<String> CHARACTER_CONCEPT_RIGHT_HINTS = Set.of(
            "本身", "之后", "失效", "稳定", "代价", "效果", "反噬", "关键时刻", "机制", "本质", "作用", "会让"
    );
    private static final Set<String> CHARACTER_POSITIVE_LEFT_HINTS = Set.of(
            "妻子", "丈夫", "女儿", "儿子", "母亲", "父亲", "妈妈", "爸爸", "哥哥", "姐姐",
            "弟弟", "妹妹", "老板", "老板娘", "同事", "老师", "同学", "丈夫的", "妻子的"
    );
    private static final Set<String> CHARACTER_NEGATIVE_LEFT_HINTS = Set.of(
            "在", "回", "到", "进", "出", "住", "留", "困在", "藏在", "躲在", "窝在", "活在",
            "回到", "来到", "走到", "先回", "送到", "带到", "待在"
    );
    private static final Set<String> COMMON_NON_NAME_BOUNDARY_BIGRAMS = Set.of(
            "觉得", "认为", "开始", "继续", "准备", "决定", "发现", "表示", "强调", "担心", "感到",
            "想到", "想起", "看到", "看见", "听到", "听见", "回到", "走到", "来到", "进去", "进入", "出来", "离开",
            "低头", "抬头", "回头", "转头", "转身", "开口", "张口", "伸手", "抬手", "点头", "摇头", "皱眉",
            "看向", "望向", "看着", "望着", "听着", "说着", "笑着", "哭着", "带着", "跟着", "牵着", "抱着",
            "站在", "坐在", "跪在", "躺在", "停在", "走进", "走出", "冲进", "冲出", "扑向", "压向",
            "问道", "说道", "笑道", "哭道", "喊道", "叫道", "答道", "拿起", "放下", "起身", "坐下", "站起",
            "踏出", "踏入", "赶去", "赶来", "赶回", "察觉", "端坐", "命令", "下达", "守在", "醒来",
            "没多", "没再", "没看", "没说", "没动", "没回", "未动", "未回", "未再", "未曾"
    );
    private static final String CHARACTER_INLINE_LEFT_BOUNDARIES =
            " \t\r\n\"'“”‘’《》「」『』（）()【】、，。！？；：和与跟对向给把将被由替朝往从是为";
    private static final String CHARACTER_INLINE_RIGHT_BOUNDARIES =
            " \t\r\n\"'“”‘’《》「」『』（）()【】、，。！？；：的和与跟在向给把将被都也仍并则让令叫说问笑哭想看听道呢吗啊呀曾会能可便就又还已正来去过着了";
    private static final String TITLED_CHARACTER_EXTRA_LEFT_BOUNDARIES = "的让叫请跟着向朝对给把将为替陪令催同和与随被";
    private static final Set<String> WORLD_SUFFIX_STOP = Set.of("身体", "神魂", "丹田", "子宫", "肉身", "魔胎", "小腹");
    private static final Set<String> WORLD_ABSTRACT_STOPWORDS = Set.of(
            "天地", "力量", "气息", "局势", "虚空", "真相", "天赋", "修为", "实力", "命运", "故事", "章节", "世界"
    );
    private static final Set<String> WORLD_GENERIC_NAMES = Set.of(
            "公司", "集团", "组织", "学院", "监狱",
            "功法", "法术", "术法", "秘法", "秘术", "邪术", "阵法", "心法", "口诀", "招式",
            "规则", "体系", "力量",
            "山门", "主殿", "大殿", "内殿", "外殿", "门外", "门口", "院内", "院外", "主峰"
    );
    private static final List<String> WORLD_SUFFIXES = List.of(
            "监狱", "学院", "组织", "公司", "集团",
            "宗", "门", "派", "阁", "宫", "殿", "城", "国", "界", "山", "谷", "岛", "洲", "域", "族",
            "诀", "功", "术", "法", "塔", "府", "院", "堂", "会", "盟"
    );
    private static final Set<String> WORLD_SUSPICIOUS_PREFIXES = Set.of(
            "在", "向", "往", "到", "进", "入", "出", "回", "来", "去", "从", "把", "将", "被", "给", "对",
            "一座", "一片", "一处", "一间", "一条", "整座", "整片", "整个",
            "这里", "那里", "这座", "那座", "这片", "那片", "这个", "那个", "这些", "那些",
            "他们", "她们", "我们", "你们"
    );
    private static final Set<String> WORLD_SUSPICIOUS_SUBSTRINGS = Set.of(
            "将他", "将她", "将它", "将其", "把他", "把她", "把它", "把其",
            "带到", "送到", "逃到", "赶到", "回到", "来到", "走到", "跑到", "冲到", "拖到", "押到", "抬到", "拉到",
            "前往", "去往", "赶往", "进入", "进到", "潜入", "踏入", "闯入", "冲进", "拖进", "押进", "带进", "送进",
            "救出", "拖出", "抬出", "救回", "困在", "锁在", "留在", "住在", "藏进", "躲进",
            "并带", "并将", "并送", "并把", "然后", "于是", "一路",
            "看清", "望向", "抬头", "低头", "回头", "守在", "终于", "弟子守", "那座", "这座", "那片", "这片",
            "自己", "这里", "那里", "这个", "那个", "这些", "那些", "他们", "她们", "我们", "你们",
            "像是", "仿佛", "好像", "如同", "整座", "整片", "半边", "远处", "高处", "低处", "近处", "深处", "边缘"
    );
    private static final Set<Character> WORLD_FUNCTION_CHARS = Set.of(
            '的', '地', '得', '了', '着', '过', '并', '将', '把', '被', '给', '向', '从', '对', '在', '和', '与', '跟', '而', '却', '还', '又'
    );
    private static final Set<Character> WORLD_PRONOUN_CHARS = Set.of('我', '你', '他', '她', '它');
    private static final Set<Character> WORLD_CLASSIFIER_LEADING_CHARS = Set.of('个', '座', '片', '处', '间', '条');
    private static final Set<Character> WORLD_SCENE_DESCRIPTOR_PREFIX_CHARS = Set.of(
            '半', '整', '偏', '远', '近', '高', '低', '深', '更', '前', '后', '左', '右', '上', '下',
            '这', '那', '此', '其'
    );
    private static final Set<Character> WORLD_SCENE_DESCRIPTOR_SUFFIX_CHARS = Set.of(
            '边', '处', '旁', '里', '外', '前', '后', '上', '下'
    );
    private static final Set<String> WORLD_SCENE_DESCRIPTOR_BIGRAMS = Set.of(
            "半边", "远处", "近处", "高处", "低处", "深处", "边缘", "整座", "整片", "整条", "旁边", "后头", "前头"
    );
    private static final Set<String> COMPOUND_CHINESE_SURNAMES = Set.of(
            "万俟", "司马", "上官", "欧阳", "夏侯", "诸葛", "闻人", "东方", "赫连", "皇甫",
            "尉迟", "公羊", "澹台", "公冶", "宗政", "濮阳", "淳于", "单于", "太叔", "申屠",
            "公孙", "仲孙", "轩辕", "令狐", "钟离", "宇文", "长孙", "慕容", "鲜于", "闾丘",
            "司徒", "司空", "丌官", "司寇", "子车", "颛孙", "端木", "巫马", "公西", "漆雕",
            "乐正", "壤驷", "公良", "拓跋", "夹谷", "宰父", "谷梁", "段干", "百里", "东郭",
            "南门", "呼延", "归海", "羊舌", "微生", "梁丘", "左丘", "东门", "西门", "南宫",
            "第五"
    );
    private static final Set<Character> SINGLE_CHINESE_SURNAMES = toCharSet(
            "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜"
                    + "戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳"
                    + "酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍"
                    + "余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋"
                    + "茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林"
                    + "刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗"
                    + "丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊於惠甄"
                    + "曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗"
                    + "班仰秋仲伊宫宁仇栾暴甘斜厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟"
                    + "薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴鬱胥能苍双闻莘党翟"
                    + "谭贡劳逄姬申扶堵冉宰郦雍卻璩桑桂濮牛寿通边扈燕冀郏浦尚农温"
                    + "别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿"
                    + "满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛"
                    + "阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"
    );
    private static final Set<String> CONCEPTUAL_WORLD_SUFFIXES = Set.of("功", "术", "法", "诀");

    private final ContextSelectEngine selectEngine;
    private final CanonStorage canonStorage;
    private final AdaptiveHintResolver adaptiveHintResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArchivistAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                          ContextSelectEngine selectEngine, CanonStorage canonStorage) {
        super(llmGateway, cardStorage, draftStorage, "zh");
        this.selectEngine = selectEngine;
        this.canonStorage = canonStorage;
        this.adaptiveHintResolver = new AdaptiveHintResolver(cardStorage, draftStorage, canonStorage);
    }

    @Override
    public String getAgentName() {
        return "archivist";
    }

    @Override
    public String getSystemPrompt() {
        return """
                ### 角色定位
                你是墨序 MoXu 系统的 Archivist（资料管理员），一位精通信息结构化的知识工程师。
                核心职责：将文本内容转换为可落库的结构化信息，并为撰稿人准备写作所需的场景简报。
                
                ### 专业能力
                - 擅长：信息抽取、结构化转换、一致性维护、知识图谱构建
                - 输出类型：事实/时间线/角色状态/摘要/设定卡/场景简报
                
                ==================================================
                ### 核心约束（信息保真原则）
                ==================================================
                
                【P0-必须】证据约束：
                  - 仅依据输入内容进行抽取
                  - 禁止捏造任何输入未明确包含的信息
                  - 不确定时：留空 / 空列表 / 降低置信度
                
                【P0-必须】输出格式：
                  - 严格可解析（JSON 或 YAML）
                  - 禁止添加 Markdown 格式、代码块、解释说明
                  - 禁止输出思维过程
                
                【P0-必须】Schema 遵循：
                  - 键名和类型必须与指定 schema 完全匹配
                  - 不添加额外字段，不省略必需字段
                
                ────────────────────────────────────────
                ### 信息抽取策略
                
                【P1-应当】优先抽取（对后文有约束力的信息）：
                  - 规则/禁忌/代价（世界观硬约束）
                  - 关键关系变化
                  - 重要状态转变
                  - 关键事件节点
                
                【P1-应当】避免抽取：
                  - 琐碎重复信息
                  - 推测性内容（推测不能当事实）
                
                【P1-应当】命名一致性：
                  - 使用输入中出现的原名
                  - 禁止擅自改名或翻译
                
                ### 自检清单（内部执行）
                
                □ 输出是否严格符合 schema？
                □ 是否包含多余的说明文字？
                □ 是否存在「输入没有但觉得合理」的捏造？
                
                ────────────────────────────────────────
                【关键约束重复 - 请务必遵守】
                【P0-必须】仅依据输入内容抽取，禁止捏造
                【P0-必须】严格输出 JSON，不添加任何额外文字
                【P0-必须】键名和类型必须与指定 schema 完全匹配
                
                你必须严格按照以下 JSON 格式输出，不要添加任何额外文字：
                {
                  "title": "章节标题",
                  "goal": "章节目标的具体化描述",
                  "characters": [
                    {"name": "角色名", "relevant_traits": "该角色的关键特征和当前状态"}
                  ],
                  "world_constraints": ["世界观约束1", "世界观约束2"],
                  "facts": ["相关事实1", "相关事实2"],
                  "style_reminder": "文风提醒，包括写作风格、语气、节奏等",
                  "timeline_context": {
                    "before": "前文发生了什么（上一章结尾）",
                    "current": "本章需要推进的情节",
                    "after": ""
                  },
                  "forbidden": ["绝对不能违反的禁区1", "禁区2"]
                }
                """;
    }

    /**
     * 执行档案员任务 - 生成场景简报
     */
    public Map<String, Object> execute(String projectId, String chapter, Map<String, Object> context) {
        String chapterTitle = (String) context.getOrDefault("chapter_title", "");
        String chapterGoal = (String) context.getOrDefault("chapter_goal", "");
        @SuppressWarnings("unchecked")
        List<String> characterNames = (List<String>) context.getOrDefault("characters", List.of());

        try {
            // 1. 收集上下文
            List<ContextItem> criticalItems = selectEngine.deterministicSelect(projectId, "archivist");

            // 2. 检索相关设定
            String query = chapterGoal + " " + String.join(" ", characterNames);
            List<ContextItem> relevantItems = selectEngine.retrievalSelect(
                    projectId, query,
                    List.of("character", "world", "fact", "summary"),
                    10
            );

            // 3. 获取前文摘要
            List<ChapterSummary> previousSummaries = getPreviousSummaries(projectId, chapter, 3);

            // 3.1 获取近期时间线事件（对齐 Python：scene_brief 不只看 summary，也看 timeline）
            List<Map<String, Object>> recentTimelineEvents = getTimelineEventsNearChapter(projectId, chapter, 3, 8);

            // 4. 获取相关事实
            List<Map<String, Object>> facts = getRelevantFacts(projectId, chapterGoal, characterNames);

            // 4.1 构建确定性场景简报（对标 Python 版：即使 LLM 表现一般，也先把可确定字段补起来）
            SceneBrief deterministicBrief = buildDeterministicSceneBrief(
                    projectId,
                    chapter,
                    chapterTitle,
                    chapterGoal,
                    characterNames,
                    previousSummaries,
                    recentTimelineEvents
            );

            // 5. 构建消息
            List<Map<String, String>> messages = buildSceneBriefMessages(
                    chapter, chapterTitle, chapterGoal, characterNames,
                    criticalItems, relevantItems, previousSummaries, facts, recentTimelineEvents, deterministicBrief
            );

            // 6. 调用 LLM
            LlmResponse response = callLlm(messages);
            log.info("Archivist LLM response length: {}", response.getContent().length());

            // 7. 解析场景简报（JSON 解析）
            SceneBrief llmBrief = parseSceneBriefFromJson(response.getContent(), chapter, chapterTitle, chapterGoal);
            SceneBrief sceneBrief = mergeSceneBrief(llmBrief, deterministicBrief);

            return Map.of(
                    "success", true,
                    "scene_brief", sceneBrief
            );

        } catch (Exception e) {
            log.error("档案员执行失败", e);
            // 回退：构建基础简报
            SceneBrief fallback = buildFallbackSceneBrief(chapter, chapterTitle, chapterGoal, characterNames);
            return Map.of("success", true, "scene_brief", fallback);
        }
    }

    private SceneBrief buildDeterministicSceneBrief(String projectId,
                                                    String chapter,
                                                    String chapterTitle,
                                                    String chapterGoal,
                                                    List<String> explicitCharacterNames,
                                                    List<ChapterSummary> previousSummaries,
                                                    List<Map<String, Object>> recentTimelineEvents) {
        List<String> previousChapters = getPreviousChapterIds(projectId, chapter, 4);
        List<String> previousTexts = loadChapterTexts(projectId, previousChapters);

        List<String> mentionTexts = new ArrayList<>();
        if (chapterTitle != null && !chapterTitle.isBlank()) {
            mentionTexts.add(chapterTitle);
        }
        if (chapterGoal != null && !chapterGoal.isBlank()) {
            mentionTexts.add(chapterGoal);
        }
        if (previousSummaries != null) {
            for (ChapterSummary summary : previousSummaries) {
                if (summary == null) {
                    continue;
                }
                StringBuilder block = new StringBuilder();
                if (summary.getTitle() != null && !summary.getTitle().isBlank()) {
                    block.append(summary.getTitle()).append('\n');
                }
                if (summary.getBriefSummary() != null && !summary.getBriefSummary().isBlank()) {
                    block.append(summary.getBriefSummary());
                }
                if (block.length() > 0) {
                    mentionTexts.add(block.toString());
                }
            }
        }
        for (String text : previousTexts) {
            if (text != null && !text.isBlank()) {
                mentionTexts.add(text.length() > 4000 ? text.substring(0, 4000) : text);
            }
        }
        for (Map<String, Object> event : recentTimelineEvents == null ? List.<Map<String, Object>>of() : recentTimelineEvents) {
            String eventText = timelineEventSearchText(event);
            if (!eventText.isBlank()) {
                mentionTexts.add(eventText);
            }
        }

        List<String> selectedCharacterNames = mergeUnique(
                extractMentionsFromTexts(projectId, mentionTexts, "character"),
                extractParticipants(recentTimelineEvents),
                explicitCharacterNames
        );
        List<String> selectedWorldNames = extractMentionsFromTexts(projectId, mentionTexts, "world");

        List<Map<String, String>> characters = buildCharacterContext(projectId, selectedCharacterNames);
        List<String> worldConstraints = buildWorldConstraintsFromNames(projectId, selectedWorldNames);
        List<String> facts = selectRelevantFactStatements(projectId, chapter, mentionTexts, 6);
        Map<String, String> timelineContext = buildTimelineContextFromSummaries(chapterGoal, previousSummaries, recentTimelineEvents);
        String styleReminder = buildStyleReminder(projectId);

        SceneBrief brief = buildFallbackSceneBrief(chapter, chapterTitle, chapterGoal, explicitCharacterNames);
        brief.setCharacters(characters);
        brief.setWorldConstraints(worldConstraints);
        brief.setFacts(facts);
        brief.setTimelineContext(timelineContext);
        brief.setStyleReminder(styleReminder);
        return brief;
    }

    private SceneBrief mergeSceneBrief(SceneBrief primary, SceneBrief fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        SceneBrief merged = new SceneBrief();
        merged.setChapter(firstNonBlank(primary.getChapter(), fallback.getChapter()));
        merged.setTitle(firstNonBlank(primary.getTitle(), fallback.getTitle()));
        merged.setGoal(firstNonBlank(primary.getGoal(), fallback.getGoal()));
        merged.setConflict(firstNonBlank(primary.getConflict(), fallback.getConflict()));
        merged.setStyleReminder(firstNonBlank(primary.getStyleReminder(), fallback.getStyleReminder()));
        merged.setKeyBeats(nonEmpty(primary.getKeyBeats()) ? primary.getKeyBeats() : fallback.getKeyBeats());
        merged.setForbidden(nonEmpty(primary.getForbidden()) ? primary.getForbidden() : fallback.getForbidden());
        merged.setCharacters(nonEmpty(primary.getCharacters()) ? primary.getCharacters() : fallback.getCharacters());
        merged.setFacts(nonEmpty(primary.getFacts()) ? primary.getFacts() : fallback.getFacts());
        merged.setWorldConstraints(nonEmpty(primary.getWorldConstraints()) ? primary.getWorldConstraints() : fallback.getWorldConstraints());
        merged.setTimelineContext(nonEmptyMap(primary.getTimelineContext()) ? primary.getTimelineContext() : fallback.getTimelineContext());
        return merged;
    }

    private List<String> getPreviousChapterIds(String projectId, String currentChapter, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<String> chapters = draftStorage.listChapters(projectId);
            if (chapters.isEmpty()) {
                return List.of();
            }
            String canonicalCurrent = currentChapter == null ? "" : currentChapter.trim();
            if (chapters.contains(canonicalCurrent)) {
                int index = chapters.indexOf(canonicalCurrent);
                int start = Math.max(0, index - limit);
                return new ArrayList<>(chapters.subList(start, index));
            }

            List<String> previous = new ArrayList<>();
            for (String chapter : chapters) {
                if (compareChapters(chapter, canonicalCurrent) < 0) {
                    previous.add(chapter);
                }
            }
            int start = Math.max(0, previous.size() - limit);
            return new ArrayList<>(previous.subList(start, previous.size()));
        } catch (Exception e) {
            log.debug("获取前文章节失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> loadChapterTexts(String projectId, List<String> chapters) {
        List<String> texts = new ArrayList<>();
        if (chapters == null || chapters.isEmpty()) {
            return texts;
        }
        for (String chapter : chapters) {
            if (chapter == null || chapter.isBlank()) {
                continue;
            }
            String text = draftStorage.getFinalDraft(projectId, chapter).orElseGet(() ->
                    draftStorage.getLatestDraft(projectId, chapter)
                            .map(draft -> draft.getContent() != null ? draft.getContent() : "")
                            .orElse("")
            );
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private List<String> extractMentionsFromTexts(String projectId, List<String> texts, String cardType) {
        List<String> names;
        if ("world".equals(cardType)) {
            names = cardStorage.listWorldCards(projectId);
        } else {
            names = cardStorage.listCharacterCards(projectId);
        }
        if (names.isEmpty() || texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<String> mentioned = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            for (String text : texts) {
                if (text != null && !text.isBlank() && text.contains(name)) {
                    if (!mentioned.contains(name)) {
                        mentioned.add(name);
                    }
                    break;
                }
            }
        }
        return mentioned;
    }

    private List<Map<String, String>> buildCharacterContext(String projectId, List<String> names) {
        List<Map<String, String>> characters = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return characters;
        }
        for (String name : names) {
            cardStorage.getCharacterCard(projectId, name).ifPresent(card -> {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("name", card.getName());
                String traits = card.getDescription() != null ? card.getDescription().trim() : "";
                canonStorage.getCharacterState(projectId, card.getName()).ifPresent(state -> {
                    String stateText = String.valueOf(state.getOrDefault("state", "")).trim();
                    String location = String.valueOf(state.getOrDefault("location", "")).trim();
                    if (!stateText.isBlank()) {
                        StringBuilder sb = new StringBuilder(traits);
                        if (sb.length() > 0) {
                            sb.append("；");
                        }
                        sb.append("当前状态：").append(stateText);
                        if (!location.isBlank()) {
                            sb.append("（地点：").append(location).append("）");
                        }
                        item.put("relevant_traits", sb.toString());
                    } else {
                        item.put("relevant_traits", traits);
                    }
                });
                item.putIfAbsent("relevant_traits", traits);
                if (item.get("name") != null && !item.get("name").isBlank()) {
                    characters.add(item);
                }
            });
        }
        return characters;
    }

    private List<String> buildWorldConstraintsFromNames(String projectId, List<String> names) {
        List<String> constraints = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return constraints;
        }
        for (String name : names) {
            cardStorage.getWorldCard(projectId, name).ifPresent(card -> {
                String description = card.getDescription() != null ? card.getDescription().trim() : "";
                String ruleText = description;
                if (card.getRules() != null && !card.getRules().isEmpty()) {
                    String joinedRules = card.getRules().stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .reduce((a, b) -> a + "；" + b)
                            .orElse("");
                    if (!joinedRules.isBlank()) {
                        ruleText = description.isBlank() ? joinedRules : description + "；规则：" + joinedRules;
                    }
                }
                if (!ruleText.isBlank()) {
                    constraints.add(card.getName() + ": " + ruleText);
                }
            });
        }
        return constraints;
    }

    private List<String> selectRelevantFactStatements(String projectId,
                                                      String chapter,
                                                      List<String> texts,
                                                      int limit) {
        try {
            List<Fact> allFacts = canonStorage.listFacts(projectId);
            if (allFacts.isEmpty()) {
                return List.of();
            }

            List<String> keywords = extractKeywords(String.join(" ", texts == null ? List.of() : texts));
            String previousChapter = null;
            List<String> previousChapters = getPreviousChapterIds(projectId, chapter, 1);
            if (!previousChapters.isEmpty()) {
                previousChapter = previousChapters.get(previousChapters.size() - 1);
            }

            List<FactCandidate> selected = new ArrayList<>();
            Set<String> seenStatements = new LinkedHashSet<>();

            for (Fact fact : allFacts) {
                String statement = fact.getStatement() != null && !fact.getStatement().isBlank()
                        ? fact.getStatement().trim()
                        : (fact.getContent() != null ? fact.getContent().trim() : "");
                if (statement.isBlank()) {
                    continue;
                }
                String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
                if (previousChapter != null && previousChapter.equalsIgnoreCase(factChapter) && seenStatements.add(statement)) {
                    selected.add(new FactCandidate(statement, 100));
                }
            }

            if (selected.size() < limit) {
                for (Fact fact : allFacts) {
                    String statement = fact.getStatement() != null && !fact.getStatement().isBlank()
                            ? fact.getStatement().trim()
                            : (fact.getContent() != null ? fact.getContent().trim() : "");
                    if (statement.isBlank() || seenStatements.contains(statement)) {
                        continue;
                    }
                    int score = scoreTextMatch(statement, keywords);
                    if (score > 0) {
                        selected.add(new FactCandidate(statement, score));
                        seenStatements.add(statement);
                    }
                }
            }

            selected.sort((a, b) -> Integer.compare(b.score(), a.score()));
            List<String> result = new ArrayList<>();
            for (FactCandidate candidate : selected) {
                result.add(candidate.statement());
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("筛选相关事实失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> buildTimelineContextFromSummaries(String chapterGoal,
                                                                  List<ChapterSummary> previousSummaries,
                                                                  List<Map<String, Object>> fallbackEvents) {
        Map<String, String> context = new LinkedHashMap<>();
        String before = "";
        if (previousSummaries != null && !previousSummaries.isEmpty()) {
            ChapterSummary summary = previousSummaries.get(previousSummaries.size() - 1);
            String title = summary.getTitle() != null ? summary.getTitle().trim() : "";
            String brief = summary.getBriefSummary() != null ? summary.getBriefSummary().trim() : "";
            before = title.isBlank() ? brief : (brief.isBlank() ? title : title + "： " + brief);
        }
        List<String> eventLines = recentTimelineEventLines(fallbackEvents, 2);
        if (before.isBlank()) {
            before = String.join("；", eventLines);
        } else if (!eventLines.isEmpty()) {
            String lastEvent = eventLines.get(eventLines.size() - 1);
            if (!lastEvent.isBlank() && !before.contains(lastEvent)) {
                before = truncateInline(before + "；关键节点：" + String.join("；", eventLines), 220);
            }
        }
        context.put("before", before);
        context.put("current", chapterGoal != null ? chapterGoal : "");
        context.put("after", "");
        return context;
    }

    private String buildStyleReminder(String projectId) {
        return cardStorage.getStyleCard(projectId)
                .map(card -> card.getStyle() != null ? card.getStyle().trim() : "")
                .orElse("");
    }

    private List<Map<String, Object>> getTimelineEventsNearChapter(String projectId,
                                                                   String chapter,
                                                                   int window,
                                                                   int maxEvents) {
        try {
            List<String> previousChapters = getPreviousChapterIds(projectId, chapter, Math.max(window, 1));
            if (previousChapters.isEmpty()) {
                return List.of();
            }
            Set<String> allowed = new LinkedHashSet<>(previousChapters);
            List<Map<String, Object>> events = canonStorage.listTimelineEvents(projectId).stream()
                    .filter(Objects::nonNull)
                    .filter(item -> allowed.contains(String.valueOf(item.getOrDefault("source", "")).trim()))
                    .sorted((a, b) -> {
                        String sourceA = String.valueOf(a.getOrDefault("source", "")).trim();
                        String sourceB = String.valueOf(b.getOrDefault("source", "")).trim();
                        int chapterCmp = compareChapters(sourceA, sourceB);
                        if (chapterCmp != 0) {
                            return chapterCmp;
                        }
                        String timeA = String.valueOf(a.getOrDefault("time", "")).trim();
                        String timeB = String.valueOf(b.getOrDefault("time", "")).trim();
                        return timeA.compareTo(timeB);
                    })
                    .toList();
            if (events.size() <= maxEvents) {
                return events;
            }
            return new ArrayList<>(events.subList(events.size() - maxEvents, events.size()));
        } catch (Exception e) {
            log.debug("获取近期时间线失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> extractParticipants(List<Map<String, Object>> events) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> event : events == null ? List.<Map<String, Object>>of() : events) {
            Object participants = event.get("participants");
            if (!(participants instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                String name = String.valueOf(item == null ? "" : item).trim();
                if (!name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private List<String> recentTimelineEventLines(List<Map<String, Object>> events, int limit) {
        if (events == null || events.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = Math.max(0, events.size() - limit);
        for (Map<String, Object> event : events.subList(start, events.size())) {
            String line = formatTimelineEventLine(event);
            if (!line.isBlank() && !lines.contains(line)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String formatTimelineEventLine(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        String source = compactInlineText(String.valueOf(event.getOrDefault("source", "")));
        String time = compactInlineText(String.valueOf(event.getOrDefault("time", "")));
        String value = compactInlineText(String.valueOf(event.getOrDefault("event", "")));
        String location = compactInlineText(String.valueOf(event.getOrDefault("location", "")));
        if (value.isBlank()) {
            return "";
        }
        String anchor = !time.isBlank() ? time : source;
        StringBuilder sb = new StringBuilder();
        if (!anchor.isBlank()) {
            sb.append(anchor).append("：");
        }
        sb.append(value);
        if (!location.isBlank()) {
            sb.append("（").append(location).append("）");
        }
        return truncateInline(sb.toString(), 140);
    }

    private String timelineEventSearchText(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return "";
        }
        List<String> pieces = new ArrayList<>();
        pieces.add(compactInlineText(String.valueOf(event.getOrDefault("event", ""))));
        pieces.add(compactInlineText(String.valueOf(event.getOrDefault("location", ""))));
        Object participants = event.get("participants");
        if (participants instanceof List<?> list) {
            for (Object item : list) {
                String name = compactInlineText(String.valueOf(item == null ? "" : item));
                if (!name.isBlank()) {
                    pieces.add(name);
                }
            }
        }
        return compactInlineText(String.join(" ", pieces));
    }

    private List<String> characterStateContextLines(SceneBrief deterministicBrief, int limit) {
        if (deterministicBrief == null || deterministicBrief.getCharacters() == null || limit <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> character : deterministicBrief.getCharacters()) {
            if (character == null || character.isEmpty()) {
                continue;
            }
            String name = compactInlineText(character.getOrDefault("name", ""));
            String traits = compactInlineText(character.getOrDefault("relevant_traits", ""));
            if (name.isBlank() || traits.isBlank() || !traits.contains("当前状态：")) {
                continue;
            }
            lines.add(truncateInline(name + "： " + traits, 180));
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines;
    }

    private String compactInlineText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncateInline(String text, int maxChars) {
        String normalized = compactInlineText(text);
        if (normalized.length() <= Math.max(maxChars, 1)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(maxChars, 1)) + "...";
    }

    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{2,}|[\\u4e00-\\u9fff]{2,}").matcher(text);
        List<String> keywords = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank() && !keywords.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }

    private int scoreTextMatch(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    @SafeVarargs
    private final <T> List<T> mergeUnique(List<T>... groups) {
        List<T> merged = new ArrayList<>();
        for (List<T> group : groups) {
            if (group == null) {
                continue;
            }
            for (T item : group) {
                if (item != null && !merged.contains(item)) {
                    merged.add(item);
                }
            }
        }
        return merged;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean nonEmpty(List<?> value) {
        return value != null && !value.isEmpty();
    }

    private boolean nonEmptyMap(Map<?, ?> value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (Object item : value.values()) {
            if (item != null && !String.valueOf(item).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private record FactCandidate(String statement, int score) {}
    private record CanonFactCandidate(String statement, double confidence, double score, boolean simpleRelation, boolean lowValue) {}

    private List<Map<String, Object>> getRelevantFacts(String projectId, String chapterGoal, List<String> characterNames) {
        try {
            List<com.moxu.model.entity.Fact> facts = canonStorage.listFacts(projectId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (com.moxu.model.entity.Fact f : facts) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", f.getId());
                m.put("statement", f.getStatement() != null ? f.getStatement() : f.getContent());
                m.put("content", f.getContent());
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            log.warn("获取事实失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ChapterSummary> getPreviousSummaries(String projectId, String chapter, int limit) {
        try {
            List<ChapterSummary> allSummaries = draftStorage.listChapterSummaries(projectId, null);
            List<ChapterSummary> previous = new ArrayList<>();
            for (ChapterSummary summary : allSummaries) {
                if (compareChapters(summary.getChapter(), chapter) < 0) {
                    previous.add(summary);
                }
            }
            int start = Math.max(0, previous.size() - limit);
            return previous.subList(start, previous.size());
        } catch (Exception e) {
            log.warn("获取前文摘要失败: {}", e.getMessage());
            return List.of();
        }
    }

    private int compareChapters(String a, String b) {
        try {
            int[] parsedA = parseChapter(a);
            int[] parsedB = parseChapter(b);
            if (parsedA[0] != parsedB[0]) return Integer.compare(parsedA[0], parsedB[0]);
            return Integer.compare(parsedA[1], parsedB[1]);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    private int[] parseChapter(String chapter) {
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
            int ch = Integer.parseInt(upper.substring(cIdx + 1));
            return new int[]{vol, ch};
        }
        return new int[]{1, 1};
    }

    private List<Map<String, String>> buildSceneBriefMessages(
            String chapter, String chapterTitle, String chapterGoal, List<String> characterNames,
            List<ContextItem> criticalItems, List<ContextItem> relevantItems,
            List<ChapterSummary> previousSummaries, List<Map<String, Object>> facts,
            List<Map<String, Object>> recentTimelineEvents, SceneBrief deterministicBrief) {

        List<String> contextItems = new ArrayList<>();

        // 前文摘要
        if (!previousSummaries.isEmpty()) {
            StringBuilder sb = new StringBuilder("【前文摘要】\n");
            for (ChapterSummary s : previousSummaries) {
                sb.append("- ").append(s.getChapter()).append(": ").append(s.getBriefSummary()).append("\n");
            }
            contextItems.add(sb.toString());
        }

        // 角色卡片（critical 优先，去重）
        Set<String> addedCharacters = new HashSet<>();
        for (ContextItem item : criticalItems) {
            if ("character_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedCharacters.add(name)) {
                    contextItems.add("【角色】" + name + "\n" + item.getContent());
                }
            }
        }
        for (ContextItem item : relevantItems) {
            if ("character_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedCharacters.add(name)) {
                    contextItems.add("【角色】" + name + "\n" + item.getContent());
                }
            }
        }

        // 世界观卡片（去重）
        Set<String> addedWorlds = new HashSet<>();
        for (ContextItem item : relevantItems) {
            if ("world_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedWorlds.add(name)) {
                    contextItems.add("【世界观】" + name + "\n" + item.getContent());
                }
            }
        }

        // 文风卡
        for (ContextItem item : criticalItems) {
            if ("style_card".equals(item.getType().getValue())) {
                contextItems.add("【文风卡】\n" + item.getContent());
            }
        }

        // 相关事实（最多10条）
        if (!facts.isEmpty()) {
            StringBuilder sb = new StringBuilder("【已知事实】\n");
            int count = 0;
            for (Map<String, Object> fact : facts) {
                String stmt = (String) fact.getOrDefault("statement", fact.getOrDefault("content", ""));
                if (stmt != null && !stmt.isBlank()) {
                    sb.append("- ").append(stmt).append("\n");
                    if (++count >= 10) break;
                }
            }
            contextItems.add(sb.toString());
        }

        // 近期时间线（对齐 Python：scene_brief 直接参考 timeline，而非只看 summaries）
        List<String> timelineLines = recentTimelineEventLines(recentTimelineEvents, 6);
        if (!timelineLines.isEmpty()) {
            StringBuilder sb = new StringBuilder("【近期时间线】\n");
            for (String line : timelineLines) {
                sb.append("- ").append(line).append("\n");
            }
            contextItems.add(sb.toString());
        }

        // 角色最新状态（显式注入给 LLM，避免只在 fallback 中可见）
        List<String> stateLines = characterStateContextLines(deterministicBrief, 6);
        if (!stateLines.isEmpty()) {
            StringBuilder sb = new StringBuilder("【角色最新状态】\n");
            for (String line : stateLines) {
                sb.append("- ").append(line).append("\n");
            }
            contextItems.add(sb.toString());
        }

        String userPrompt = String.format("""
                请为以下章节生成场景简报（严格输出 JSON，不要有任何额外文字）：
                
                章节ID: %s
                章节标题: %s
                章节目标: %s
                涉及角色: %s
                """,
                chapter, chapterTitle, chapterGoal,
                characterNames.isEmpty() ? "未指定" : String.join("、", characterNames)
        );

        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }

    /**
     * 从 LLM 输出的 JSON 解析 SceneBrief
     */
    private SceneBrief parseSceneBriefFromJson(String content, String chapter, String defaultTitle, String defaultGoal) {
        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(defaultTitle);
        brief.setGoal(defaultGoal);
        brief.setCharacters(new ArrayList<>());
        brief.setWorldConstraints(new ArrayList<>());
        brief.setFacts(new ArrayList<>());
        brief.setForbidden(new ArrayList<>());
        brief.setTimelineContext(new HashMap<>());

        if (content == null || content.isBlank()) return brief;

        // 提取 JSON 块（可能被 ```json ... ``` 包裹）
        String json = extractJson(content);
        if (json == null) {
            log.warn("Archivist: 无法从 LLM 输出中提取 JSON，原始内容: {}", content.substring(0, Math.min(200, content.length())));
            return brief;
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            // title
            if (root.has("title") && !root.get("title").isNull()) {
                String t = root.get("title").asText("").trim();
                if (!t.isEmpty()) brief.setTitle(t);
            }

            // goal
            if (root.has("goal") && !root.get("goal").isNull()) {
                String g = root.get("goal").asText("").trim();
                if (!g.isEmpty()) brief.setGoal(g);
            }

            // style_reminder
            if (root.has("style_reminder") && !root.get("style_reminder").isNull()) {
                brief.setStyleReminder(root.get("style_reminder").asText("").trim());
            }

            // characters
            if (root.has("characters") && root.get("characters").isArray()) {
                List<Map<String, String>> characters = new ArrayList<>();
                for (JsonNode charNode : root.get("characters")) {
                    Map<String, String> charMap = new HashMap<>();
                    charMap.put("name", charNode.path("name").asText(""));
                    charMap.put("relevant_traits", charNode.path("relevant_traits").asText(""));
                    if (!charMap.get("name").isEmpty()) {
                        characters.add(charMap);
                    }
                }
                brief.setCharacters(characters);
            }

            // world_constraints
            if (root.has("world_constraints") && root.get("world_constraints").isArray()) {
                List<String> wc = new ArrayList<>();
                for (JsonNode n : root.get("world_constraints")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) wc.add(s);
                }
                brief.setWorldConstraints(wc);
            }

            // facts
            if (root.has("facts") && root.get("facts").isArray()) {
                List<String> factList = new ArrayList<>();
                for (JsonNode n : root.get("facts")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) factList.add(s);
                }
                brief.setFacts(factList);
            }

            // forbidden
            if (root.has("forbidden") && root.get("forbidden").isArray()) {
                List<String> forbiddenList = new ArrayList<>();
                for (JsonNode n : root.get("forbidden")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) forbiddenList.add(s);
                }
                brief.setForbidden(forbiddenList);
            }

            // timeline_context
            if (root.has("timeline_context") && root.get("timeline_context").isObject()) {
                Map<String, String> tc = new HashMap<>();
                JsonNode tcNode = root.get("timeline_context");
                tc.put("before", tcNode.path("before").asText(""));
                tc.put("current", tcNode.path("current").asText(""));
                tc.put("after", tcNode.path("after").asText(""));
                brief.setTimelineContext(tc);
            }

            log.info("Archivist: 场景简报解析成功 - title={}, characters={}, facts={}",
                    brief.getTitle(), brief.getCharacters().size(), brief.getFacts().size());

        } catch (Exception e) {
            log.error("Archivist: JSON 解析失败: {}", e.getMessage());
        }

        return brief;
    }

    /**
     * 从文本中提取 JSON 块
     */
    private String extractJson(String text) {
        if (text == null) return null;

        // 尝试 ```json ... ``` 代码块
        Pattern codeBlock = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher m = codeBlock.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }

        // 尝试直接找 { ... }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }

    /**
     * 纯启发式检测草稿中的新设定建议（对齐 Python detect_setting_changes，无 LLM 调用）
     */
    public List<Map<String, Object>> detectSettingChanges(String draftContent, List<String> existingCardNames) {
        return detectSettingChanges(draftContent, existingCardNames, existingCardNames);
    }

    /**
     * 纯启发式检测草稿中的新设定建议（对齐 Python detect_setting_changes，无 LLM 调用）
     * 现阶段策略：
     * - world 继续保持宽松检测
     * - character 只新增高置信、强姓名结构信号的人物
     */
    public List<Map<String, Object>> detectSettingChanges(String draftContent,
                                                          List<String> existingCharacterCardNames,
                                                          List<String> existingWorldCardNames) {
        if (draftContent == null || draftContent.isBlank()) return List.of();

        Set<String> existingCharacters = normalizeNames(existingCharacterCardNames);
        Set<String> existingWorld = normalizeNames(existingWorldCardNames);
        List<String> sentences = splitSentences(draftContent);

        Map<String, WorldProposalCandidate> worldCandidates = extractWorldCandidates(draftContent);
        Map<String, CharacterProposalCandidate> characterCandidates = extractCharacterCandidates(draftContent);

        List<Map<String, Object>> proposals = new ArrayList<>();
        proposals.addAll(buildCharacterProposals(characterCandidates, existingCharacters));
        proposals.addAll(buildCardProposals(worldCandidates, "World", existingWorld, sentences, 2));
        proposals.sort(Comparator.comparingDouble(this::proposalConfidence).reversed());
        return proposals;
    }

    private List<String> splitSentences(String text) {
        String[] parts = text.split("[。！？\n]");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private Map<String, WorldProposalCandidate> extractWorldCandidates(String text) {
        Map<String, WorldProposalCandidate> counts = new LinkedHashMap<>();
        String normalized = text == null ? "" : text;

        for (String entity : HanlpLexiconSupport.extractWorldEntityTokens(normalized)) {
            for (String name : extractNestedWorldCandidates(entity)) {
                if (isSafeExplicitWorldToken(name)) {
                    counts.computeIfAbsent(normalizeKey(name), ignored -> new WorldProposalCandidate(name))
                            .bump("hanlp_world");
                }
            }
        }

        Matcher matcher = WORLD_TOKEN.matcher(normalized);
        while (matcher.find()) {
            for (String name : extractNestedWorldCandidates(matcher.group(1))) {
                if (isSafeExplicitWorldToken(name) && hasLexicallySupportedWorldOccurrence(normalized, name)) {
                    counts.computeIfAbsent(normalizeKey(name), ignored -> new WorldProposalCandidate(name))
                            .bump("world_suffix_rule");
                }
            }
        }

        return counts;
    }

    private List<Map<String, Object>> buildCardProposals(Map<String, WorldProposalCandidate> candidates, String cardType,
                                                          Set<String> existing, List<String> sentences, int minCount) {
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (WorldProposalCandidate candidate : candidates.values()) {
            String name = candidate.name();
            int count = candidate.mentions();
            if (name.isBlank() || existing.contains(normalizeKey(name)) || !isAcceptableWorldProposalName(name)) continue;

            int requiredCount = minCount;
            if (isConceptualWorldName(name) && name.length() <= 3) {
                requiredCount += 1;
            }
            if (count < requiredCount) continue;

            String sourceSentence = sentences.stream().filter(s -> s.contains(name)).findFirst().orElse("");
            if (sourceSentence.isBlank()) continue;

            double confidence = Math.min(0.9, 0.5 + 0.1 * Math.min(count, 4));
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("name", name);
            proposal.put("type", cardType);
            proposal.put("description", sourceSentence);
            proposal.put("rationale", "在本章中多次出现（" + count + " 次），具备可复用设定价值。");
            proposal.put("source_text", sourceSentence);
            proposal.put("confidence", confidence);
            proposal.put("confidence_explanation", buildWorldConfidenceExplanation(candidate, confidence));
            proposal.put("source_kind", candidate.primaryEvidenceKind());
            proposal.put("evidence_kinds", candidate.evidenceKinds());
            proposals.add(proposal);
        }
        return proposals;
    }

    private List<Map<String, Object>> buildCharacterProposals(Map<String, CharacterProposalCandidate> candidates,
                                                              Set<String> existingCharacterNames) {
        return candidates.values().stream()
                .filter(this::passesHighConfidenceCharacterThreshold)
                .sorted(Comparator.comparingDouble(CharacterProposalCandidate::score).reversed())
                .filter(candidate -> !existingCharacterNames.contains(normalizeKey(candidate.name())))
                .limit(6)
                .map(this::toCharacterProposal)
                .toList();
    }

    private Map<String, CharacterProposalCandidate> extractCharacterCandidates(String text) {
        Map<String, CharacterProposalCandidate> candidates = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return candidates;
        }
        String normalized = text.replace("\r", "");
        Set<String> explicitWorldTokens = extractExplicitWorldTokens(normalized);
        for (String clause : normalized.split("[，。！？；：\n]")) {
            String trimmed = clause == null ? "" : clause.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            for (CharacterCandidateHit hit : extractCharacterNameCandidates(trimmed, explicitWorldTokens)) {
                String token = hit.name();
                CharacterSignal signal = analyzeCharacterCandidateSignal(token, trimmed);
                if (signal.rejected()) {
                    continue;
                }
                double structureSignal = characterStructureSignal(hit.sourceKind());
                double adjustedWeight = Math.max(0.35d,
                        1.0d + signal.positiveScore() * 0.45d - signal.negativeScore() * 0.75d + structureSignal * 0.25d);
                candidates.computeIfAbsent(normalizeKey(token), ignored -> new CharacterProposalCandidate(token))
                        .bump(adjustedWeight, trimmed, signal.positiveScore(), signal.negativeScore(),
                                structureSignal, isStrongStructureSource(hit.sourceKind()), hit.sourceKind());
            }
        }
        return candidates;
    }

    private Set<String> extractExplicitWorldTokens(String text) {
        LinkedHashSet<String> results = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return results;
        }
        Matcher matcher = WORLD_TOKEN.matcher(text);
        while (matcher.find()) {
            String rawToken = matcher.group(1);
            if (rawToken == null || rawToken.isBlank()) {
                continue;
            }
            for (String token : extractNestedWorldCandidates(rawToken)) {
                if (isSafeExplicitWorldToken(token)) {
                    results.add(token);
                }
            }
        }
        return results;
    }

    private boolean isSafeExplicitWorldToken(String token) {
        if (token == null || token.isBlank() || !isAcceptableWorldProposalName(token)) {
            return false;
        }
        String body = stripWorldSuffix(token);
        if (body.isBlank()) {
            return false;
        }
        if (body.length() >= 2 && CHARACTER_DISCOURSE_ENDING_CHARS.contains(body.charAt(body.length() - 1))) {
            return false;
        }
        if (containsNumericLikeHan(body)) {
            return false;
        }
        if (looksLikeNarrativeSwallowedWorldToken(body)) {
            return false;
        }
        return true;
    }

    private boolean looksLikeNarrativeSwallowedWorldToken(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        if (containsTooManyWorldFunctionChars(body)) {
            return true;
        }
        int maxPrefix = Math.min(3, body.length() - 1);
        for (int len = 2; len <= maxPrefix; len++) {
            String prefix = body.substring(0, len);
            if (!matchesChineseNameStructure(prefix)) {
                continue;
            }
            String tail = body.substring(len);
            if (tail.isBlank()) {
                continue;
            }
            char first = tail.charAt(0);
            char last = tail.charAt(tail.length() - 1);
            if (WORLD_FUNCTION_CHARS.contains(first)
                    || CHARACTER_DISCOURSE_ENDING_CHARS.contains(last)
                    || formsCommonNarrativeBigram(prefix.charAt(prefix.length() - 1), first)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNumericLikeHan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ("0123456789零〇一二两三四五六七八九十百千万".indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }

    private List<CharacterCandidateHit> extractCharacterNameCandidates(String text, Set<String> explicitWorldTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, CharacterCandidateHit> results = new LinkedHashMap<>();
        for (String token : extractSegmentedCharacterCandidates(text, explicitWorldTokens)) {
            addCharacterHit(results, token, "ner");
        }
        String leading = extractLeadingCharacterCandidate(text, explicitWorldTokens);
        if (!leading.isBlank()) {
            addCharacterHit(results, leading, "leading");
        }
        Matcher speech = SPEECH_NAME.matcher(text);
        while (speech.find()) {
            String token = speech.group(1);
            if (isValidCharacterCandidate(token, text, explicitWorldTokens)) {
                addCharacterHit(results, token, "speech");
            }
        }
        for (String token : extractTitledCharacterCandidates(text, explicitWorldTokens)) {
            addCharacterHit(results, token, "titled");
        }
        for (String token : extractInlineCharacterCandidates(text, explicitWorldTokens)) {
            addCharacterHit(results, token, "inline");
        }
        return new ArrayList<>(results.values());
    }

    private List<String> extractSegmentedCharacterCandidates(String text, Set<String> explicitWorldTokens) {
        LinkedHashSet<String> results = new LinkedHashSet<>();
        for (String token : HanlpLexiconSupport.extractPersonTokens(text)) {
            boolean plainBareName = !matchesSurnameWithCharacterTitle(token, true)
                    && !hasNicknameStyleStructure(token)
                    && !hasCompoundSurnameStructure(token);
            if (plainBareName
                    && token.length() <= 3
                    && !appearsWithStandaloneCharacterBoundary(token, text)) {
                continue;
            }
            if (token.length() == 2
                    && !matchesSurnameWithCharacterTitle(token, true)
                    && !hasNicknameStyleStructure(token)
                    && !appearsWithStandaloneCharacterBoundary(token, text)) {
                continue;
            }
            if (isValidCharacterCandidate(token, text, explicitWorldTokens)) {
                results.add(token);
            }
        }
        return new ArrayList<>(results);
    }

    private void addCharacterHit(Map<String, CharacterCandidateHit> hits, String token, String sourceKind) {
        if (hits == null || token == null || token.isBlank()) {
            return;
        }
        CharacterCandidateHit incoming = new CharacterCandidateHit(token.trim(), sourceKind == null ? "inline" : sourceKind.trim());
        String key = normalizeKey(incoming.name());
        CharacterCandidateHit existing = hits.get(key);
        if (existing == null || characterSourcePriority(incoming.sourceKind()) > characterSourcePriority(existing.sourceKind())) {
            hits.put(key, incoming);
        }
    }

    private int characterSourcePriority(String sourceKind) {
        if (sourceKind == null || sourceKind.isBlank()) {
            return 0;
        }
        return switch (sourceKind) {
            case "speech" -> 5;
            case "titled" -> 4;
            case "leading" -> 3;
            case "inline" -> 2;
            case "ner" -> 1;
            default -> 0;
        };
    }

    private double characterStructureSignal(String sourceKind) {
        if (sourceKind == null || sourceKind.isBlank()) {
            return 0.0d;
        }
        return switch (sourceKind) {
            case "speech" -> 1.6d;
            case "titled" -> 1.4d;
            case "leading" -> 1.2d;
            case "inline" -> 1.0d;
            case "ner" -> 0.55d;
            default -> 0.0d;
        };
    }

    private boolean isStrongStructureSource(String sourceKind) {
        return "speech".equals(sourceKind)
                || "titled".equals(sourceKind)
                || "leading".equals(sourceKind);
    }

    private List<String> extractTitledCharacterCandidates(String clause, Set<String> explicitWorldTokens) {
        if (clause == null || clause.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> results = new LinkedHashSet<>();
        for (int start = 0; start < clause.length(); start++) {
            int remaining = clause.length() - start;
            if (remaining < 2) {
                break;
            }
            for (int len = Math.min(4, remaining); len >= 2; len--) {
                String token = clause.substring(start, start + len);
                if (!matchesSurnameWithCharacterTitle(token, true) || !isLikelyStandaloneCharacterName(token)) {
                    continue;
                }
                char prev = start > 0 ? clause.charAt(start - 1) : '\0';
                char next = start + len < clause.length() ? clause.charAt(start + len) : '\0';
                if (!isTitledCharacterLeftBoundary(prev)) {
                    continue;
                }
                if (conflictsWithExplicitWorldToken(token, explicitWorldTokens)) {
                    continue;
                }
                if (formsCommonNarrativeBigram(token.charAt(token.length() - 1), next)) {
                    continue;
                }
                results.add(token);
                start += len - 1;
                break;
            }
        }
        return new ArrayList<>(results);
    }

    private List<String> extractInlineCharacterCandidates(String clause, Set<String> explicitWorldTokens) {
        if (clause == null || clause.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> results = new LinkedHashSet<>();
        for (int start = 0; start < clause.length(); start++) {
            int remaining = clause.length() - start;
            if (remaining < 2) {
                break;
            }
            for (int len = Math.min(4, remaining); len >= 2; len--) {
                String token = clause.substring(start, start + len);
                if (!matchesChineseNameStructure(token) || !isLikelyStandaloneCharacterName(token)) {
                    continue;
                }
                char prev = start > 0 ? clause.charAt(start - 1) : '\0';
                char next = start + len < clause.length() ? clause.charAt(start + len) : '\0';
                if (!isInlineCharacterLeftBoundary(prev) || !isInlineCharacterRightBoundary(next)) {
                    continue;
                }
                if (formsCommonNarrativeBigram(token.charAt(token.length() - 1), next)) {
                    continue;
                }
                if (conflictsWithExplicitWorldToken(token, explicitWorldTokens)) {
                    continue;
                }
                results.add(token);
                start += len - 1;
                break;
            }
        }
        return new ArrayList<>(results);
    }

    private String extractLeadingCharacterCandidate(String clause, Set<String> explicitWorldTokens) {
        if (clause == null || clause.isBlank()) {
            return "";
        }
        String cleaned = clause.replaceFirst("^[“\"'《「『（(【]+", "");
        List<HanlpLexiconSupport.TokenInfo> tokens = HanlpLexiconSupport.tokenize(cleaned);
        for (HanlpLexiconSupport.TokenInfo token : tokens) {
            String word = token.word();
            if (word == null || word.isBlank()) {
                continue;
            }
            if (!cleaned.startsWith(word)) {
                break;
            }
            boolean personNature = HanlpLexiconSupport.isPersonNature(token.nature());
            boolean nounishNature = HanlpLexiconSupport.isNounishNature(token.nature());
            if (!personNature && !nounishNature) {
                break;
            }
            boolean strongNameShape = matchesSurnameWithCharacterTitle(word, true)
                    || hasNicknameStyleStructure(word)
                    || hasCompoundSurnameStructure(word);
            if (isValidCharacterCandidate(word, cleaned, explicitWorldTokens)
                    && isPlausibleLeadingCharacterName(word, word)
                    && (personNature || (strongNameShape && isStrongLeadingCharacterCandidate(word, cleaned)))) {
                return word;
            }
            break;
        }
        Matcher matcher = LEADING_HAN_BLOCK.matcher(cleaned);
        if (!matcher.find()) {
            return "";
        }
        String leadingHan = matcher.group();
        int maxLen = Math.min(Math.min(4, leadingHan.length()), cleaned.length());
        for (int len = maxLen; len >= 2; len--) {
            String candidate = leadingHan.substring(0, len);
            if (!isValidCharacterCandidate(candidate, cleaned, explicitWorldTokens)) {
                continue;
            }
            if (!isStrongLeadingCharacterCandidate(candidate, cleaned)) {
                continue;
            }
            if (!isPlausibleLeadingCharacterName(candidate, leadingHan)) {
                continue;
            }
            return candidate;
        }
        return "";
    }

    private boolean isStrongLeadingCharacterCandidate(String candidate, String clause) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (matchesSurnameWithCharacterTitle(candidate, true)
                || hasCompoundSurnameStructure(candidate)
                || hasNicknameStyleStructure(candidate)) {
            return true;
        }
        if (clause == null || clause.length() <= candidate.length()) {
            return false;
        }
        String right = clause.substring(candidate.length()).trim();
        return startsWithAny(right, LEADING_CHARACTER_ACTION_HINTS)
                || startsWithAny(right, CHARACTER_POSITIVE_RIGHT_HINTS)
                || looksLikeHumanSubjectPattern(right);
    }

    private boolean isValidCharacterCandidate(String token, String clause, Set<String> explicitWorldTokens) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4) {
            return false;
        }
        if (!matchesChineseNameStructure(trimmed) || !isLikelyStandaloneCharacterName(trimmed)) {
            return false;
        }
        if (conflictsWithExplicitWorldToken(trimmed, explicitWorldTokens)) {
            return false;
        }
        return clause == null || !clause.startsWith("本章" + trimmed);
    }

    private boolean appearsWithStandaloneCharacterBoundary(String token, String clause) {
        if (token == null || token.isBlank() || clause == null || clause.isBlank()) {
            return false;
        }
        int index = clause.indexOf(token);
        while (index >= 0) {
            char prev = index > 0 ? clause.charAt(index - 1) : '\0';
            char next = index + token.length() < clause.length() ? clause.charAt(index + token.length()) : '\0';
            if (isInlineCharacterLeftBoundary(prev) && isInlineCharacterRightBoundary(next)) {
                return true;
            }
            index = clause.indexOf(token, index + token.length());
        }
        return false;
    }

    private boolean isLikelyStandaloneCharacterName(String token) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4 || !isAllHan(trimmed)) {
            return false;
        }
        if (CHARACTER_STOPWORDS.contains(trimmed)
                || CHARACTER_HARD_NEGATIVE_TOKENS.contains(trimmed)
                || WORLD_SUFFIX_STOP.contains(trimmed)
                || looksLikeWorldToken(trimmed)
                || isLowValueWorldCandidate(trimmed)
                || isTemporalNarrativeToken(trimmed)) {
            return false;
        }
        char first = trimmed.charAt(0);
        return "这那其某每各该另再还并但却而因为所以如果虽然于是就在与及和被把将向从对".indexOf(first) < 0;
    }

    private CharacterSignal analyzeCharacterCandidateSignal(String token, String clause) {
        if (token == null || token.isBlank()) {
            return new CharacterSignal(0.0d, 0.0d, true);
        }
        String trimmed = token.trim();
        String normalizedClause = clause == null ? "" : clause.replaceAll("\\s+", "");

        double positive = 0.0d;
        double negative = 0.0d;
        boolean hardReject = CHARACTER_HARD_NEGATIVE_TOKENS.contains(trimmed);

        if (matchesSurnameWithCharacterTitle(trimmed, false)) {
            positive += 1.6d;
        } else if (matchesSurnameWithCharacterTitle(trimmed, true)) {
            positive += 0.9d;
        }

        if (isLikelyLocationLikeCharacterToken(trimmed)) {
            negative += 1.4d;
        }

        int tokenLength = trimmed.length();
        int searchFrom = 0;
        while (searchFrom >= 0 && searchFrom < normalizedClause.length()) {
            int index = normalizedClause.indexOf(trimmed, searchFrom);
            if (index < 0) {
                break;
            }

            String left = normalizedClause.substring(Math.max(0, index - 4), index);
            String right = normalizedClause.substring(index + tokenLength, Math.min(normalizedClause.length(), index + tokenLength + 6));

            if (startsWithAny(right, CHARACTER_POSITIVE_RIGHT_HINTS)) {
                positive += index == 0 ? 1.9d : 1.4d;
            }
            if (looksLikeHumanSubjectPattern(right)) {
                positive += index == 0 ? 1.5d : 1.1d;
            }
            if (startsWithAny(right, CHARACTER_NEGATIVE_RIGHT_HINTS)) {
                negative += index == 0 ? 3.1d : 1.8d;
            }
            if (startsWithAny(right, CHARACTER_CONCEPT_RIGHT_HINTS)) {
                negative += index == 0 ? 3.4d : 2.1d;
            }
            if (endsWithAny(left, CHARACTER_POSITIVE_LEFT_HINTS)) {
                positive += 2.2d;
            }
            if (endsWithAny(left, CHARACTER_NEGATIVE_LEFT_HINTS)) {
                negative += 2.1d;
            }
            if (endsWithAny(left, CHARACTER_CONCEPT_LEFT_HINTS)) {
                negative += 2.3d;
            }

            searchFrom = index + tokenLength;
        }

        boolean rejected = hardReject || (negative >= 3.0d && positive < 1.0d);
        return new CharacterSignal(positive, negative, rejected);
    }

    private boolean isLikelyLocationLikeCharacterToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        if (!CHARACTER_LOCATION_ENDING_CHARS.contains(last)) {
            return false;
        }
        return trimmed.length() <= 3 || trimmed.charAt(0) == '家' || trimmed.charAt(0) == '屋' || trimmed.charAt(0) == '房'
                || trimmed.charAt(0) == '门' || trimmed.charAt(0) == '路' || trimmed.charAt(0) == '床' || trimmed.charAt(0) == '桌'
                || trimmed.charAt(0) == '窗' || trimmed.charAt(0) == '眼' || trimmed.charAt(0) == '身' || trimmed.charAt(0) == '心';
    }

    private Set<String> extractNestedWorldCandidates(String rawToken) {
        LinkedHashSet<String> results = new LinkedHashSet<>();
        if (rawToken == null || rawToken.isBlank()) {
            return results;
        }
        String trimmed = rawToken.trim();
        for (int start = 0; start < trimmed.length(); start++) {
            int remaining = trimmed.length() - start;
            if (remaining < 3) {
                break;
            }
            for (int len = Math.min(12, remaining); len >= 3; len--) {
                String candidate = trimmed.substring(start, start + len);
                String normalized = normalizeWorldCandidateToken(candidate);
                if (normalized.isBlank() || !isAcceptableWorldProposalName(normalized)) {
                    continue;
                }
                results.add(normalized);
            }
        }
        return results;
    }

    private boolean looksLikeWorldToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (WORLD_SUFFIX_STOP.contains(token) || WORLD_GENERIC_NAMES.contains(token)) {
            return true;
        }
        return WORLD_TOKEN.matcher(token).matches();
    }

    private String normalizeWorldCandidateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String trimmed = rawToken.trim();
        String best = "";
        for (int start = 0; start < trimmed.length(); start++) {
            String candidate = trimmed.substring(start);
            if (candidate.length() < 3 || candidate.length() > 12) {
                continue;
            }
            if (!WORLD_TOKEN.matcher(candidate).matches()) {
                continue;
            }
            if (isLowValueWorldCandidate(candidate)) {
                continue;
            }
            if (candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isAcceptableWorldProposalName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        if (WORLD_GENERIC_NAMES.contains(trimmed) || isLowValueWorldCandidate(trimmed)) {
            return false;
        }
        return WORLD_TOKEN.matcher(trimmed).matches();
    }

    private boolean isLowValueWorldCandidate(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || WORLD_SUFFIX_STOP.contains(trimmed) || WORLD_GENERIC_NAMES.contains(trimmed)) {
            return true;
        }
        if (WORLD_ABSTRACT_STOPWORDS.contains(trimmed) || CHARACTER_STOPWORDS.contains(trimmed)) {
            return true;
        }
        if (looksLikeSceneDescriptorWorldBody(stripWorldSuffix(trimmed))) {
            return true;
        }
        if (hasSuspiciousWorldPrefix(trimmed) || containsSuspiciousWorldPhrase(trimmed)) {
            return true;
        }
        String suffix = matchedWorldSuffix(trimmed);
        String body = stripWorldSuffix(trimmed);
        if (!body.isBlank() && containsTooManyWorldFunctionChars(body)) {
            return true;
        }
        return suffix != null && CONCEPTUAL_WORLD_SUFFIXES.contains(suffix) && body.length() < 2;
    }

    private boolean looksLikeSceneDescriptorWorldBody(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("像是")
                || trimmed.startsWith("仿佛")
                || trimmed.startsWith("好像")
                || trimmed.startsWith("如同")) {
            return true;
        }
        if (trimmed.startsWith("是整")
                || trimmed.startsWith("是半")
                || trimmed.startsWith("是远")
                || trimmed.startsWith("是高")
                || trimmed.startsWith("是低")
                || trimmed.startsWith("是近")
                || trimmed.startsWith("是深")
                || trimmed.startsWith("是边")) {
            return true;
        }
        if (trimmed.contains("整座")
                || trimmed.contains("整片")
                || trimmed.contains("半边")
                || trimmed.contains("远处")
                || trimmed.contains("高处")
                || trimmed.contains("低处")
                || trimmed.contains("近处")
                || trimmed.contains("深处")
                || trimmed.contains("边缘")) {
            return true;
        }
        if (trimmed.length() > 3) {
            return false;
        }
        if (WORLD_SCENE_DESCRIPTOR_BIGRAMS.contains(trimmed)) {
            return true;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        return WORLD_SCENE_DESCRIPTOR_PREFIX_CHARS.contains(first)
                || WORLD_SCENE_DESCRIPTOR_SUFFIX_CHARS.contains(last);
    }

    private String stripWorldSuffix(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        for (String suffix : WORLD_SUFFIXES) {
            if (token.endsWith(suffix) && token.length() > suffix.length()) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }

    private String matchedWorldSuffix(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (String suffix : WORLD_SUFFIXES) {
            if (token.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    private boolean hasSuspiciousWorldPrefix(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (WORLD_CLASSIFIER_LEADING_CHARS.contains(token.charAt(0)) && token.length() > 2) {
            return true;
        }
        for (String prefix : WORLD_SUSPICIOUS_PREFIXES) {
            if (token.startsWith(prefix) && token.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSuspiciousWorldPhrase(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String phrase : WORLD_SUSPICIOUS_SUBSTRINGS) {
            if (token.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTooManyWorldFunctionChars(String tokenBody) {
        if (tokenBody == null || tokenBody.length() < 4) {
            return false;
        }
        int hits = 0;
        for (int i = 0; i < tokenBody.length(); i++) {
            char ch = tokenBody.charAt(i);
            if (WORLD_FUNCTION_CHARS.contains(ch) || WORLD_PRONOUN_CHARS.contains(ch)) {
                hits++;
                if (hits >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConceptualWorldName(String token) {
        String suffix = matchedWorldSuffix(token);
        return suffix != null && CONCEPTUAL_WORLD_SUFFIXES.contains(suffix);
    }

    private boolean hasLexicallySupportedWorldOccurrence(String text, String candidate) {
        if (text == null || text.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        for (String clause : text.split("[，。！？；：\\n]")) {
            String trimmed = clause == null ? "" : clause.trim();
            if (trimmed.isBlank() || !trimmed.contains(candidate)) {
                continue;
            }
            if (matchesLexicallySupportedWorldSpan(trimmed, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLexicallySupportedWorldSpan(String clause, String candidate) {
        List<HanlpLexiconSupport.TokenInfo> tokens = HanlpLexiconSupport.tokenize(clause);
        if (tokens.isEmpty()) {
            return false;
        }
        for (int start = 0; start < tokens.size(); start++) {
            StringBuilder span = new StringBuilder();
            boolean allTokensPlausible = true;
            boolean hasEntityLikeToken = false;
            for (int end = start; end < Math.min(tokens.size(), start + 4); end++) {
                HanlpLexiconSupport.TokenInfo token = tokens.get(end);
                String word = token.word();
                if (word == null || word.isBlank()) {
                    continue;
                }
                span.append(word);
                String current = span.toString();
                if (!candidate.startsWith(current)) {
                    break;
                }
                boolean plausibleNature = HanlpLexiconSupport.isWorldEntityNature(token.nature())
                        || HanlpLexiconSupport.isNounishNature(token.nature());
                allTokensPlausible = allTokensPlausible && plausibleNature;
                hasEntityLikeToken = hasEntityLikeToken || plausibleNature;
                if (candidate.equals(current) && isPlausibleWorldTokenSpan(tokens, start, end, candidate,
                        allTokensPlausible, hasEntityLikeToken)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPlausibleWorldTokenSpan(List<HanlpLexiconSupport.TokenInfo> tokens,
                                              int start,
                                              int end,
                                              String candidate,
                                              boolean allTokensPlausible,
                                              boolean hasEntityLikeToken) {
        if (candidate == null || candidate.isBlank() || start < 0 || end < start || end >= tokens.size()) {
            return false;
        }
        if (start == end) {
            return allTokensPlausible && hasEntityLikeToken;
        }
        if (allTokensPlausible && hasEntityLikeToken) {
            return true;
        }
        String suffix = matchedWorldSuffix(candidate);
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        String lastWord = tokens.get(end).word();
        if (!suffix.equals(lastWord)) {
            return false;
        }
        boolean bodySupported = false;
        for (int i = start; i < end; i++) {
            HanlpLexiconSupport.TokenInfo token = tokens.get(i);
            boolean plausible = HanlpLexiconSupport.isWorldEntityNature(token.nature())
                    || HanlpLexiconSupport.isNounishNature(token.nature());
            if (!plausible) {
                return false;
            }
            bodySupported = true;
        }
        return bodySupported;
    }

    private boolean isPlausibleLeadingCharacterName(String candidate, String leadingHan) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (!matchesChineseNameStructure(candidate)) {
            return false;
        }
        if (likelySwallowedNarrativePhrase(candidate, leadingHan)) {
            return false;
        }
        char last = candidate.charAt(candidate.length() - 1);
        return !CHARACTER_DISCOURSE_ENDING_CHARS.contains(last);
    }

    private boolean matchesChineseNameStructure(String token) {
        if (token == null || token.isBlank() || !isAllHan(token)) {
            return false;
        }
        int length = token.length();
        if (length < 2 || length > 4) {
            return false;
        }
        if (matchesSurnameWithCharacterTitle(token, true)) {
            return true;
        }
        if (hasCompoundSurnameStructure(token)) {
            return true;
        }
        if (hasNicknameStyleStructure(token)) {
            return true;
        }
        if (length == 4) {
            return false;
        }
        return SINGLE_CHINESE_SURNAMES.contains(token.charAt(0));
    }

    private boolean hasCompoundSurnameStructure(String token) {
        return token != null
                && token.length() >= 3
                && COMPOUND_CHINESE_SURNAMES.contains(token.substring(0, 2));
    }

    private boolean hasNicknameStyleStructure(String token) {
        return token != null
                && token.length() >= 2
                && CHARACTER_NAME_PREFIX_CHARS.contains(token.charAt(0))
                && SINGLE_CHINESE_SURNAMES.contains(token.charAt(1));
    }

    private boolean matchesSurnameWithCharacterTitle(String token, boolean includeWeakTitles) {
        if (token == null || token.isBlank() || !isAllHan(token)) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4) {
            return false;
        }
        if (hasCompoundSurnameStructure(trimmed)) {
            String suffix = trimmed.substring(2);
            return STRONG_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(suffix::equals)
                    || (includeWeakTitles && WEAK_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(suffix::equals));
        }
        char first = trimmed.charAt(0);
        if (!SINGLE_CHINESE_SURNAMES.contains(first)) {
            return false;
        }
        String suffix = trimmed.substring(1);
        return STRONG_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(suffix::equals)
                || (includeWeakTitles && WEAK_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(suffix::equals));
    }

    private boolean isTemporalNarrativeToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        return TEMPORAL_NARRATIVE_TOKENS.contains(trimmed) || TEMPORAL_SEQUENCE.matcher(trimmed).matches();
    }

    private boolean likelySwallowedNarrativePhrase(String candidate, String leadingHan) {
        if (candidate == null || leadingHan == null || leadingHan.length() <= candidate.length()) {
            return false;
        }
        return formsCommonNarrativeBigram(candidate.charAt(candidate.length() - 1), leadingHan.charAt(candidate.length()));
    }

    private boolean isAllHan(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.UnicodeScript.of(token.charAt(i)) != Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    private boolean formsCommonNarrativeBigram(char previous, char next) {
        if (previous == '\0' || next == '\0') {
            return false;
        }
        return COMMON_NON_NAME_BOUNDARY_BIGRAMS.contains("" + previous + next);
    }

    private boolean isInlineCharacterLeftBoundary(char ch) {
        return ch == '\0' || CHARACTER_INLINE_LEFT_BOUNDARIES.indexOf(ch) >= 0;
    }

    private boolean isInlineCharacterRightBoundary(char ch) {
        return ch == '\0' || CHARACTER_INLINE_RIGHT_BOUNDARIES.indexOf(ch) >= 0;
    }

    private boolean isTitledCharacterLeftBoundary(char ch) {
        return isInlineCharacterLeftBoundary(ch) || TITLED_CHARACTER_EXTRA_LEFT_BOUNDARIES.indexOf(ch) >= 0;
    }

    private boolean conflictsWithExplicitWorldToken(String token, Set<String> explicitWorldTokens) {
        if (token == null || token.isBlank() || explicitWorldTokens == null || explicitWorldTokens.isEmpty()) {
            return false;
        }
        for (String worldToken : explicitWorldTokens) {
            int lengthGap = Math.abs(worldToken.length() - token.length());
            if (worldToken.equals(token) || (lengthGap <= 2 && (worldToken.startsWith(token) || token.startsWith(worldToken)))) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String text, Set<String> prefixes) {
        if (text == null || text.isBlank() || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithAny(String text, Set<String> suffixes) {
        if (text == null || text.isBlank() || suffixes == null || suffixes.isEmpty()) {
            return false;
        }
        for (String suffix : suffixes) {
            if (!suffix.isBlank() && text.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeHumanSubjectPattern(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim();
        for (String prefix : CHARACTER_SUBJECT_PREFIX_HINTS) {
            if (!normalized.startsWith(prefix)) {
                continue;
            }
            int window = Math.min(normalized.length(), 8);
            String lookahead = normalized.substring(0, window);
            for (String hint : CHARACTER_SUBJECT_ACTION_HINTS) {
                if (lookahead.contains(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean passesHighConfidenceCharacterThreshold(CharacterProposalCandidate candidate) {
        if (candidate == null || candidate.name().isBlank()) {
            return false;
        }
        if (candidate.negativeSignals() >= candidate.positiveSignals() + 2.2d) {
            return false;
        }
        if (candidate.mentions() < 2) {
            return false;
        }
        if (candidate.snippetCount() < 2) {
            return false;
        }
        boolean titleLike = matchesSurnameWithCharacterTitle(candidate.name(), true);
        boolean genericPlainName = !titleLike
                && !hasNicknameStyleStructure(candidate.name())
                && !hasCompoundSurnameStructure(candidate.name());
        if (genericPlainName
                && candidate.strongStructureMentions() == 0
                && candidate.positiveSignals() < 1.0d) {
            return false;
        }
        boolean bareTwoCharName = candidate.name().length() == 2 && !titleLike && !hasNicknameStyleStructure(candidate.name());
        if (bareTwoCharName) {
            if (candidate.strongStructureMentions() >= 1
                    && candidate.structureSignals() >= 1.8d
                    && candidate.score() >= 4.2d
                    && candidate.negativeSignals() <= 1.2d) {
                return true;
            }
            if (candidate.score() >= 5.0d
                    && candidate.positiveSignals() >= 2.0d
                    && candidate.negativeSignals() <= 0.8d) {
                return true;
            }
            return false;
        }
        return candidate.score() >= 4.2d;
    }

    private Map<String, Object> toCharacterProposal(CharacterProposalCandidate candidate) {
        String sourceText = candidate.primarySnippet();
        double confidence = Math.min(0.92d, 0.52d + candidate.score() * 0.055d);
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("name", candidate.name());
        proposal.put("type", "Character");
        proposal.put("description", buildCharacterProposalDescription(candidate));
        proposal.put("rationale", "在本章中稳定以人物方式出现（" + candidate.mentions() + " 次），适合作为角色卡沉淀。");
        proposal.put("source_text", sourceText);
        proposal.put("confidence", confidence);
        proposal.put("confidence_explanation", buildCharacterConfidenceExplanation(candidate, confidence));
        proposal.put("stars", Math.max(1, Math.min(3, candidate.mentions() >= 4 ? 3 : 2)));
        proposal.put("source_kind", candidate.primaryEvidenceKind());
        proposal.put("evidence_kinds", candidate.evidenceKinds());
        return proposal;
    }

    private String buildCharacterProposalDescription(CharacterProposalCandidate candidate) {
        String snippet = candidate.primarySnippet();
        if (!snippet.isBlank()) {
            return "本章反复出场的重要角色，常见于：" + snippet;
        }
        return "本章反复出场的重要角色，建议后续结合章节继续补全。";
    }

    private String buildCharacterConfidenceExplanation(CharacterProposalCandidate candidate, double confidence) {
        if (candidate == null) {
            return "";
        }
        return "出现 " + candidate.mentions() + " 次，命中 " + candidate.snippetCount() + " 段有效片段；"
                + "结构信号 " + formatProposalDouble(candidate.structureSignals())
                + "，正向信号 " + formatProposalDouble(candidate.positiveSignals())
                + "，负向信号 " + formatProposalDouble(candidate.negativeSignals())
                + "，综合得分 " + formatProposalDouble(candidate.score())
                + "，折算置信度 " + Math.round(confidence * 100) + "%。";
    }

    private String buildWorldConfidenceExplanation(WorldProposalCandidate candidate, double confidence) {
        if (candidate == null) {
            return "";
        }
        String evidenceText = candidate.evidenceKinds().isEmpty()
                ? "未记录命中依据"
                : String.join("、", candidate.evidenceKinds());
        return "出现 " + candidate.mentions() + " 次；命中依据 " + evidenceText
                + "；按重复次数折算置信度 " + Math.round(confidence * 100) + "%。";
    }

    private String formatProposalDouble(double value) {
        double rounded = Math.round(value * 10.0d) / 10.0d;
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            return String.valueOf((long) Math.rint(rounded));
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    private double proposalConfidence(Map<String, Object> proposal) {
        Object value = proposal.get("confidence");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private Set<String> normalizeNames(List<String> names) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (names == null || names.isEmpty()) {
            return normalized;
        }
        for (String name : names) {
            String key = normalizeKey(name);
            if (!key.isBlank()) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private SceneBrief buildFallbackSceneBrief(String chapter, String title, String goal, List<String> characterNames) {        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(title != null ? title : chapter);
        brief.setGoal(goal != null ? goal : "");
        brief.setCharacters(new ArrayList<>());
        brief.setWorldConstraints(new ArrayList<>());
        brief.setFacts(new ArrayList<>());
        brief.setForbidden(new ArrayList<>());
        brief.setTimelineContext(new HashMap<>());
        return brief;
    }

    /**
     * 生成章节摘要（对齐 Python SummaryMixin.generate_chapter_summary）
     */
    public ChapterSummary generateChapterSummary(String projectId, String chapter,
                                                   String chapterTitle, String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        }

        String retryHint = null;
        for (int attempt = 1; attempt <= CHAPTER_SUMMARY_MAX_ATTEMPTS; attempt++) {
            try {
                List<Map<String, String>> messages = buildChapterSummaryMessages(chapter, chapterTitle, finalDraft, retryHint);
                LlmResponse response = callLlm(messages);
                ChapterSummary parsed = tryParseChapterSummaryFromJson(response.getContent(), chapter, chapterTitle, finalDraft);
                parsed = supplementChapterSummary(projectId, chapter, chapterTitle, finalDraft, parsed);
                String failureReason = chapterSummaryFailureReason(parsed, finalDraft);
                if (failureReason == null && parsed != null) {
                    return parsed;
                }
                retryHint = buildSummaryRetryHint(failureReason == null ? "unknown failure" : failureReason);
                log.warn("generateChapterSummary 结果未通过校验: chapter={}, attempt={}/{}, reason={}, preview={}",
                        chapter,
                        attempt,
                        CHAPTER_SUMMARY_MAX_ATTEMPTS,
                        failureReason,
                        preview(response.getContent(), 600));
            } catch (Exception e) {
                retryHint = buildSummaryRetryHint("llm_call_failed: " + e.getMessage());
                log.warn("generateChapterSummary 失败: chapter={}, attempt={}/{}, error={}",
                        chapter, attempt, CHAPTER_SUMMARY_MAX_ATTEMPTS, e.getMessage());
            }
        }

        log.warn("generateChapterSummary 多次尝试后仍失败，使用回退: chapter={}", chapter);
        return buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
    }

    private List<Map<String, String>> buildChapterSummaryMessages(String chapter,
                                                                  String chapterTitle,
                                                                  String finalDraft,
                                                                  String retryHint) {
        String systemPrompt = """
                你是一位专业的章节摘要生成器。
                根据提供的章节正文，生成结构化的章节摘要。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "title": "章节标题",
                  "brief_summary": "100字以内的章节核心内容摘要",
                  "key_events": ["关键事件1", "关键事件2"],
                  "new_facts": ["本章新增的重要事实1"],
                  "character_state_changes": ["角色状态变化描述"],
                  "open_loops": ["未解决的悬念或伏笔"]
                }

                额外要求：
                - brief_summary 必须是概括性摘要，不能直接复制正文原句
                - open_loops 优先关注章节结尾仍未解决、能直接驱动下一章的钩子
                - 没有足够信息的字段，输出空字符串或空数组
                """;

        String userPrompt = String.format("""
                章节ID: %s
                章节标题: %s
                
                章节正文（节选）：
                %s
                
                请生成章节摘要（严格输出 JSON）：
                """,
                chapter, chapterTitle != null ? chapterTitle : chapter,
                clipChapterSummarySource(finalDraft)
        );
        if (retryHint != null && !retryHint.isBlank()) {
            userPrompt += "\n\n### Retry constraints (must follow)\n"
                    + retryHint.trim()
                    + "\n- 仅输出严格 JSON，不要 markdown 或解释。"
                    + "\n- brief_summary 必须是抽象概括，不能直接复制正文开头。";
        }
        return buildMessages(systemPrompt, userPrompt, List.of());
    }

    private String clipChapterSummarySource(String finalDraft) {
        return clipHeadAndTail(finalDraft,
                CHAPTER_SUMMARY_SOURCE_HEAD_CHARS,
                CHAPTER_SUMMARY_SOURCE_TAIL_CHARS,
                CHAPTER_SUMMARY_SOURCE_LIMIT);
    }

    private String clipCanonUpdateSource(String finalDraft) {
        return clipHeadAndTail(finalDraft,
                CANON_UPDATE_SOURCE_HEAD_CHARS,
                CANON_UPDATE_SOURCE_TAIL_CHARS,
                CANON_UPDATE_SOURCE_HEAD_CHARS + CANON_UPDATE_SOURCE_TAIL_CHARS);
    }

    private String clipOpenLoopTailSource(String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return "";
        }
        String normalized = finalDraft.trim();
        if (normalized.length() <= OPEN_LOOP_TAIL_CHARS) {
            return normalized;
        }
        return "（仅提供章节结尾片段，优先提取结尾遗留钩子）\n"
                + normalized.substring(normalized.length() - OPEN_LOOP_TAIL_CHARS);
    }

    private String clipCharacterStateSource(String finalDraft) {
        return clipHeadAndTail(finalDraft,
                CHARACTER_STATE_SOURCE_HEAD_CHARS,
                CHARACTER_STATE_SOURCE_TAIL_CHARS,
                CHARACTER_STATE_SOURCE_HEAD_CHARS + CHARACTER_STATE_SOURCE_TAIL_CHARS);
    }

    private String clipHeadAndTail(String text, int headChars, int tailChars, int hardLimit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim();
        int safeHardLimit = Math.max(hardLimit, 1);
        if (normalized.length() <= safeHardLimit) {
            return normalized;
        }
        int safeHead = Math.max(0, headChars);
        int safeTail = Math.max(0, tailChars);
        if (safeHead + safeTail <= 0) {
            return normalized.substring(0, Math.min(normalized.length(), safeHardLimit));
        }
        if (normalized.length() <= safeHead + safeTail + 32) {
            return normalized;
        }
        String head = normalized.substring(0, Math.min(safeHead, normalized.length()));
        String tail = normalized.substring(Math.max(0, normalized.length() - safeTail));
        return head + "\n\n……（中间内容省略）……\n\n" + tail;
    }

    private String buildSummaryRetryHint(String reason) {
        String detail = reason == null ? "unknown" : reason;
        if (detail.length() > 220) {
            detail = detail.substring(0, 220);
        }
        return "上一次结果未通过结构化摘要校验。失败原因：" + detail
                + "。请重新生成全部字段，尤其是 brief_summary。";
    }

    private ChapterSummary parseChapterSummaryFromJson(String content, String chapter,
                                                         String chapterTitle, String finalDraft) {
        ChapterSummary parsed = tryParseChapterSummaryFromJson(content, chapter, chapterTitle, finalDraft);
        return parsed != null ? parsed : buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
    }

    private ChapterSummary tryParseChapterSummaryFromJson(String content, String chapter,
                                                          String chapterTitle, String finalDraft) {
        if (content == null || content.isBlank()) return null;

        String json = extractJson(content);
        if (json == null) {
            log.warn("parseChapterSummaryFromJson 未找到 JSON: chapter={}, preview={}",
                    chapter, preview(content, 600));
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            ChapterSummary summary = ChapterSummary.builder()
                    .chapter(chapter)
                    .title(chapterTitle != null && !chapterTitle.isBlank() ? chapterTitle : chapter)
                    .wordCount(finalDraft != null ? finalDraft.length() : 0)
                    .keyEvents(new ArrayList<>())
                    .newFacts(new ArrayList<>())
                    .characterStateChanges(new ArrayList<>())
                    .openLoops(new ArrayList<>())
                    .build();
            String title = firstNonBlank(
                    textValue(root, "title"),
                    textValue(root, "chapter_title"),
                    textValue(root, "name")
            );
            if (!title.isBlank()) {
                summary.setTitle(title);
            }

            String brief = firstNonBlank(
                    textValue(root, "brief_summary"),
                    textValue(root, "summary"),
                    textValue(root, "brief"),
                    textValue(root, "synopsis"),
                    textValue(root, "overview")
            );
            if (!brief.isBlank()) {
                summary.setBriefSummary(brief);
            }

            List<String> events = firstNonEmptyList(
                    extractStringArray(root, List.of("key_events"), List.of("event", "summary", "text", "description")),
                    extractStringArray(root, List.of("events", "major_events", "plot_points", "key_points"), List.of("event", "summary", "text", "description"))
            );
            if (!events.isEmpty()) {
                summary.setKeyEvents(events);
            }

            List<String> facts = firstNonEmptyList(
                    extractStringArray(root, List.of("new_facts"), List.of("statement", "fact", "summary", "text", "description")),
                    extractStringArray(root, List.of("facts", "new_information", "new_info"), List.of("statement", "fact", "summary", "text", "description"))
            );
            if (!facts.isEmpty()) {
                summary.setNewFacts(facts);
            }

            List<String> changes = firstNonEmptyList(
                    extractStringArray(root, List.of("character_state_changes"), List.of("state", "summary", "text", "description")),
                    extractStringArray(root, List.of("character_states", "state_changes"), List.of("state", "summary", "text", "description"))
            );
            if (!changes.isEmpty()) {
                summary.setCharacterStateChanges(changes);
            }

            List<String> loops = firstNonEmptyList(
                    extractStringArray(root, List.of("open_loops"), List.of("question", "summary", "text", "description")),
                    extractStringArray(root, List.of("open_questions", "unresolved", "foreshadowing"), List.of("question", "summary", "text", "description"))
            );
            if (!loops.isEmpty()) {
                summary.setOpenLoops(loops);
            }
            return summary;
        } catch (Exception e) {
            log.warn("parseChapterSummaryFromJson 解析失败: chapter={}, error={}, preview={}",
                    chapter, e.getMessage(), preview(json, 600));
            return null;
        }
    }

    private String chapterSummaryFailureReason(ChapterSummary summary, String finalDraft) {
        if (summary == null) {
            return "invalid JSON/schema";
        }
        String briefSummary = summary.getBriefSummary();
        if (briefSummary == null || briefSummary.isBlank()) {
            return "brief_summary missing";
        }
        if (isFallbackChapterSummary(briefSummary, finalDraft)) {
            return "brief_summary collapsed to fallback excerpt";
        }
        if (isLikelyRawExcerpt(briefSummary, finalDraft)) {
            return "brief_summary resembles leading raw draft excerpt";
        }
        return null;
    }

    private boolean isLikelyRawExcerpt(String briefSummary, String finalDraft) {
        String summary = normalizeSummaryText(briefSummary);
        String draft = normalizeSummaryText(finalDraft);
        if (summary.isBlank() || draft.isBlank() || summary.length() < RAW_SUMMARY_MIN_CHARS) {
            return false;
        }
        String head = draft.substring(0, Math.min(summary.length(), draft.length()));
        if (summary.equals(head)) {
            return true;
        }
        return sharedPrefixRatio(summary, head) >= RAW_SUMMARY_PREFIX_RATIO;
    }

    private ChapterSummary supplementChapterSummary(String projectId,
                                                    String chapter,
                                                    String chapterTitle,
                                                    String finalDraft,
                                                    ChapterSummary summary) {
        if (summary == null) {
            return null;
        }
        summary.setKeyEvents(dedupeInlineStrings(summary.getKeyEvents(), 5));
        summary.setNewFacts(dedupeInlineStrings(summary.getNewFacts(), 5));
        summary.setCharacterStateChanges(dedupeInlineStrings(summary.getCharacterStateChanges(), 4));

        List<String> loops = dedupeInlineStrings(summary.getOpenLoops(), 3);
        if (loops.isEmpty() && finalDraft != null && !finalDraft.isBlank()) {
            loops = recoverOpenLoopsWithLlm(chapter, chapterTitle, summary, finalDraft);
        }
        if (loops.isEmpty() && finalDraft != null && !finalDraft.isBlank()) {
            loops = deriveOpenLoopsFromDraft(finalDraft);
        }
        summary.setOpenLoops(dedupeInlineStrings(loops, 3));
        return summary;
    }

    private List<String> recoverOpenLoopsWithLlm(String chapter,
                                                 String chapterTitle,
                                                 ChapterSummary summary,
                                                 String finalDraft) {
        String tailExcerpt = clipOpenLoopTailSource(finalDraft);
        if (tailExcerpt.isBlank()) {
            return List.of();
        }

        String systemPrompt = """
                你是一位“章节结尾钩子提取器”。
                你的任务不是概括整章，而是只抓住“本章结尾仍未解决、且能直接驱动下一章”的悬念。

                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "open_loops": ["悬念1", "悬念2"]
                }

                规则：
                - 只提取 1~3 条真正未解决的问题/钩子
                - 优先依据章节结尾片段，不要回退成整章主题总结
                - 不要写“接下来会发生什么”这类空泛句
                - 如果结尾没有明确悬念，输出空数组
                """;

        String userPrompt = String.format("""
                章节ID: %s
                章节标题: %s
                已有摘要: %s
                已有关键事件: %s

                章节结尾片段：
                %s

                请提取本章结尾遗留的 open_loops（严格输出 JSON）：
                """,
                chapter,
                chapterTitle != null ? chapterTitle : chapter,
                summary != null ? compactInlineText(summary.getBriefSummary()) : "",
                summary != null && summary.getKeyEvents() != null ? String.join("；", summary.getKeyEvents()) : "",
                tailExcerpt
        );

        try {
            LlmResponse response = callLlm(buildMessages(systemPrompt, userPrompt, List.of()));
            return parseOpenLoopsFromJson(response.getContent());
        } catch (Exception e) {
            log.warn("recoverOpenLoopsWithLlm 失败: chapter={}, error={}", chapter, e.getMessage());
            return List.of();
        }
    }

    private List<String> parseOpenLoopsFromJson(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String json = extractJson(content);
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return dedupeInlineStrings(firstNonEmptyList(
                    extractStringArray(root, List.of("open_loops"), List.of("question", "summary", "text", "description")),
                    extractStringArray(root, List.of("open_questions", "unresolved", "foreshadowing"), List.of("question", "summary", "text", "description"))
            ), 3);
        } catch (Exception e) {
            log.warn("parseOpenLoopsFromJson 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> deriveOpenLoopsFromDraft(String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return List.of();
        }
        String tail = compactInlineText(clipOpenLoopTailSource(finalDraft));
        LinkedHashSet<String> loops = new LinkedHashSet<>();

        boolean hasMicroLight = containsAnyToken(tail, List.of("微光", "幽冷", "微青", "寒气", "火星"));
        boolean hasStoneStructure = containsAnyToken(tail, List.of("石面", "门框", "断碑", "缝隙", "轮廓", "石缝"));
        boolean hasArtificialTrace = containsAnyToken(tail, List.of("削过", "整齐", "人工", "动过手"));
        boolean hasDraggingThreat = containsAnyToken(tail, List.of("拖擦", "窸窣", "异响", "贴地一闪", "蹭过来", "逼近"));

        if (hasMicroLight && (hasStoneStructure || hasArtificialTrace)) {
            loops.add("谷底深处那截发黑石面与幽冷微光的来源究竟是什么，它是否通向隐藏空间或遗迹？");
        } else if (hasMicroLight) {
            loops.add("谷底深处那点异常微光究竟来自什么地方，又意味着什么？");
        } else if (hasStoneStructure || hasArtificialTrace) {
            loops.add("谷底里显露出的石面与人工痕迹究竟是什么，是否隐藏着可进入的隐秘空间？");
        }

        if (hasDraggingThreat) {
            loops.add("身后越来越近的拖擦异响来自什么东西，它是否已经逼近眼前的人？");
        }

        if ((hasStoneStructure || hasArtificialTrace) && containsAnyToken(tail, List.of("谷底", "崖壁", "深处"))) {
            loops.add("这处谷底是否并非天然绝地，而是埋着某种人为构造或旧日遗留之物？");
        }

        return dedupeInlineStrings(new ArrayList<>(loops), 3);
    }

    private String normalizeSummaryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double sharedPrefixRatio(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0.0d;
        }
        int maxLen = Math.min(left.length(), right.length());
        if (maxLen <= 0) {
            return 0.0d;
        }
        int index = 0;
        while (index < maxLen && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index / (double) maxLen;
    }

    private ChapterSummary repairChapterSummary(String chapter,
                                               String chapterTitle,
                                               String finalDraft,
                                               String rawContent) {
        String systemPrompt = """
                你是一位 JSON 修复器。
                你的任务不是补写正文，而是把输入中已经存在的信息整理成章节摘要 JSON。

                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "title": "章节标题",
                  "brief_summary": "100字以内的章节核心内容摘要",
                  "key_events": ["关键事件1", "关键事件2"],
                  "new_facts": ["本章新增的重要事实1"],
                  "character_state_changes": ["角色状态变化描述"],
                  "open_loops": ["未解决的悬念或伏笔"]
                }

                规则：
                - 仅可整理输入里已经明确出现的信息
                - 没有就输出空数组或空字符串
                - 不要解释，不要 markdown
                """;

        String userPrompt = String.format("""
                章节ID: %s
                章节标题: %s

                原始输出：
                %s

                请将上面的原始输出整理成合法 JSON：
                """,
                chapter,
                chapterTitle != null ? chapterTitle : chapter,
                rawContent != null && rawContent.length() > 3000 ? rawContent.substring(0, 3000) + "..." : String.valueOf(rawContent)
        );

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            ChapterSummary repaired = parseChapterSummaryFromJson(response.getContent(), chapter, chapterTitle, finalDraft);
            if (shouldRepairChapterSummary(repaired, finalDraft)) {
                log.warn("repairChapterSummary 结果仍偏弱: chapter={}, preview={}",
                        chapter, preview(response.getContent(), 600));
            }
            return repaired;
        } catch (Exception e) {
            log.warn("repairChapterSummary 失败: chapter={}, error={}", chapter, e.getMessage());
            return buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        }
    }

    private ChapterSummary mergeChapterSummary(ChapterSummary primary,
                                              ChapterSummary fallback,
                                              String chapter,
                                              String chapterTitle,
                                              String finalDraft) {
        ChapterSummary base = buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        ChapterSummary merged = new ChapterSummary();
        merged.setChapter(firstNonBlank(
                primary != null ? primary.getChapter() : "",
                fallback != null ? fallback.getChapter() : "",
                base.getChapter()
        ));
        merged.setTitle(firstNonBlank(
                primary != null ? primary.getTitle() : "",
                fallback != null ? fallback.getTitle() : "",
                base.getTitle()
        ));
        merged.setVolumeId(firstNonBlank(
                primary != null ? primary.getVolumeId() : "",
                fallback != null ? fallback.getVolumeId() : ""
        ));
        merged.setWordCount(Math.max(
                primary != null ? primary.getWordCount() : 0,
                fallback != null ? fallback.getWordCount() : 0
        ));

        String primaryBrief = primary != null ? primary.getBriefSummary() : "";
        String fallbackBrief = fallback != null ? fallback.getBriefSummary() : "";
        if (primaryBrief == null || primaryBrief.isBlank() || isFallbackChapterSummary(primaryBrief, finalDraft)) {
            merged.setBriefSummary(firstNonBlank(fallbackBrief, primaryBrief, base.getBriefSummary()));
        } else {
            merged.setBriefSummary(primaryBrief);
        }

        merged.setKeyEvents(nonEmpty(primary != null ? primary.getKeyEvents() : null)
                ? primary.getKeyEvents()
                : (fallback != null ? fallback.getKeyEvents() : base.getKeyEvents()));
        merged.setNewFacts(nonEmpty(primary != null ? primary.getNewFacts() : null)
                ? primary.getNewFacts()
                : (fallback != null ? fallback.getNewFacts() : base.getNewFacts()));
        merged.setCharacterStateChanges(nonEmpty(primary != null ? primary.getCharacterStateChanges() : null)
                ? primary.getCharacterStateChanges()
                : (fallback != null ? fallback.getCharacterStateChanges() : base.getCharacterStateChanges()));
        merged.setOpenLoops(nonEmpty(primary != null ? primary.getOpenLoops() : null)
                ? primary.getOpenLoops()
                : (fallback != null ? fallback.getOpenLoops() : base.getOpenLoops()));
        return merged;
    }

    private boolean shouldRepairChapterSummary(ChapterSummary summary, String finalDraft) {
        if (summary == null) {
            return true;
        }
        return !nonEmpty(summary.getKeyEvents())
                || isFallbackChapterSummary(summary.getBriefSummary(), finalDraft);
    }

    private boolean isFallbackChapterSummary(String briefSummary, String finalDraft) {
        if (briefSummary == null || briefSummary.isBlank() || finalDraft == null || finalDraft.isBlank()) {
            return true;
        }
        String fallback = buildFallbackChapterSummary("", "", finalDraft).getBriefSummary();
        return normalizeInlineText(briefSummary).equals(normalizeInlineText(fallback));
    }

    private String normalizeInlineText(String text) {
        return text == null ? "" : text.replace("\r", "").replace("\n", "").trim();
    }

    private String textValue(JsonNode root, String key) {
        if (root == null || key == null || key.isBlank() || !root.has(key) || root.get(key).isNull()) {
            return "";
        }
        return root.get(key).asText("").trim();
    }

    private List<String> extractStringArray(JsonNode root, List<String> keys, List<String> objectFields) {
        if (root == null || keys == null || keys.isEmpty()) {
            return List.of();
        }
        for (String key : keys) {
            if (key == null || key.isBlank() || !root.has(key) || root.get(key).isNull()) {
                continue;
            }
            JsonNode node = root.get(key);
            if (node.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : node) {
                    String value = extractSummaryItemText(item, objectFields);
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private String extractSummaryItemText(JsonNode node, List<String> objectFields) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText("").trim();
        }
        if (node.isObject()) {
            if (objectFields != null) {
                for (String field : objectFields) {
                    String value = textValue(node, field);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            String statement = normalizedFactStatement(node);
            if (!statement.isBlank()) {
                return statement;
            }
        }
        return "";
    }

    @SafeVarargs
    private final List<String> firstNonEmptyList(List<String>... candidates) {
        if (candidates == null) {
            return List.of();
        }
        for (List<String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return List.of();
    }

    private List<String> dedupeInlineStrings(List<String> values, int limit) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        int safeLimit = Math.max(limit, 1);
        for (String value : values) {
            String cleaned = compactInlineText(value);
            if (!isMeaningfulOpenLoop(cleaned) && safeLimit <= 3) {
                continue;
            }
            if (!cleaned.isBlank()) {
                unique.add(cleaned);
            }
            if (unique.size() >= safeLimit) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private boolean isMeaningfulOpenLoop(String text) {
        String normalized = compactInlineText(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.length() < 8) {
            return false;
        }
        List<String> weakPhrases = List.of("接下来会发生什么", "后续如何发展", "故事将如何展开", "之后会怎样");
        for (String phrase : weakPhrases) {
            if (normalized.contains(phrase)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAnyToken(String text, List<String> tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAll(String text, String... tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.length == 0) {
            return false;
        }
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (!text.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private void addUnique(List<String> values, String item) {
        if (values == null || item == null) {
            return;
        }
        String cleaned = compactInlineText(item);
        if (!cleaned.isBlank() && !values.contains(cleaned)) {
            values.add(cleaned);
        }
    }

    private ChapterSummary buildFallbackChapterSummary(String chapter, String chapterTitle, String content) {
        String brief = "";
        if (content != null && !content.isBlank()) {
            brief = content.length() > 100 ? content.substring(0, 100) + "..." : content;
        }
        return ChapterSummary.builder()
                .chapter(chapter)
                .title(chapterTitle != null && !chapterTitle.isBlank() ? chapterTitle : chapter)
                .briefSummary(brief)
                .wordCount(content != null ? content.length() : 0)
                .keyEvents(new ArrayList<>())
                .newFacts(new ArrayList<>())
                .characterStateChanges(new ArrayList<>())
                .openLoops(new ArrayList<>())
                .build();
    }

    /**
     * 生成分卷摘要（对齐 Python SummaryMixin.generate_volume_summary）
     * 汇总该卷所有章节摘要，生成分卷级别的摘要
     */
    public Map<String, Object> generateVolumeSummary(String projectId, String volumeId,
                                                       List<ChapterSummary> chapterSummaries) {
        Map<String, Object> fallback = buildFallbackVolumeSummary(volumeId, chapterSummaries);
        if (chapterSummaries == null || chapterSummaries.isEmpty()) {
            return fallback;
        }

        StringBuilder chaptersText = new StringBuilder();
        for (ChapterSummary cs : chapterSummaries) {
            chaptersText.append("- ").append(cs.getChapter());
            if (cs.getTitle() != null && !cs.getTitle().isBlank()) {
                chaptersText.append("《").append(cs.getTitle()).append("》");
            }
            if (cs.getBriefSummary() != null && !cs.getBriefSummary().isBlank()) {
                chaptersText.append("：").append(cs.getBriefSummary());
            }
            chaptersText.append("\n");
        }

        String systemPrompt = """
                你是一位专业的分卷摘要生成器。
                根据提供的章节摘要列表，生成该卷的整体摘要。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "volume_id": "分卷ID",
                  "title": "分卷标题（如无则留空）",
                  "brief_summary": "200字以内的分卷核心内容摘要",
                  "chapter_count": 0,
                  "total_word_count": 0,
                  "key_arcs": ["主要情节线1", "主要情节线2"],
                  "open_loops": ["未解决的悬念或伏笔"]
                }
                """;

        String userPrompt = String.format("""
                分卷ID: %s
                包含章节数: %d
                
                各章节摘要：
                %s
                
                请生成分卷摘要（严格输出 JSON）：
                """, volumeId, chapterSummaries.size(), chaptersText);

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            return parseVolumeSummaryFromJson(response.getContent(), volumeId, chapterSummaries);
        } catch (Exception e) {
            log.warn("generateVolumeSummary LLM 调用失败，使用回退: {}", e.getMessage());
            return fallback;
        }
    }

    private Map<String, Object> parseVolumeSummaryFromJson(String content, String volumeId,
                                                             List<ChapterSummary> chapterSummaries) {
        Map<String, Object> result = buildFallbackVolumeSummary(volumeId, chapterSummaries);
        if (content == null || content.isBlank()) return result;

        String json = extractJson(content);
        if (json == null) return result;

        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("title") && !root.get("title").isNull()) {
                String t = root.get("title").asText("").trim();
                if (!t.isBlank()) result.put("title", t);
            }
            if (root.has("brief_summary") && !root.get("brief_summary").isNull()) {
                result.put("brief_summary", root.get("brief_summary").asText("").trim());
            }
            if (root.has("key_arcs") && root.get("key_arcs").isArray()) {
                List<String> arcs = new ArrayList<>();
                for (JsonNode n : root.get("key_arcs")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) arcs.add(s);
                }
                result.put("key_arcs", arcs);
            }
            if (root.has("open_loops") && root.get("open_loops").isArray()) {
                List<String> loops = new ArrayList<>();
                for (JsonNode n : root.get("open_loops")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) loops.add(s);
                }
                result.put("open_loops", loops);
            }
        } catch (Exception e) {
            log.warn("parseVolumeSummaryFromJson 解析失败: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildFallbackVolumeSummary(String volumeId, List<ChapterSummary> chapterSummaries) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("volume_id", volumeId);
        summary.put("title", "");
        summary.put("brief_summary", "");
        summary.put("chapter_count", chapterSummaries != null ? chapterSummaries.size() : 0);
        int totalWords = chapterSummaries != null
                ? chapterSummaries.stream().mapToInt(cs -> cs.getWordCount() > 0 ? cs.getWordCount() : 0).sum()
                : 0;
        summary.put("total_word_count", totalWords);
        summary.put("key_arcs", new ArrayList<>());
        summary.put("open_loops", new ArrayList<>());
        return summary;
    }

    /**
     * 从最终草稿提取事实表更新（对齐 Python SummaryMixin.extract_canon_updates）
     */
    public Map<String, Object> extractCanonUpdates(String projectId, String chapter, String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }

        String systemPrompt = """
                你是一位专业的事实表提取器。
                从章节正文中提取新增的、对后续章节有约束力的结构化信息。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "facts": [
                    {
                      "statement": "事实陈述（一句话，简洁明确）",
                      "confidence": 0.9
                    }
                  ],
                  "timeline_events": [
                    {
                      "time": "时间描述",
                      "event": "事件描述",
                      "location": "地点",
                      "participants": ["参与者1"]
                    }
                  ],
                  "character_states": [
                    {
                      "character": "角色名",
                      "state": "一句话状态概述（可空）",
                      "goals": ["目标1"],
                      "injuries": ["伤势1"],
                      "inventory": ["物品1"],
                      "relationships": {"他人": "关系变化"},
                      "location": "所在地点",
                      "emotional_state": "情绪"
                    }
                  ]
                }
                
                抽取规范：
                - facts 建议 3-5 条，可少于 3 条；宁缺毋滥
                - 只保留可反复检索复用、对后续章节有约束力的高价值事实
                - 优先级：规则/禁忌/代价/秘术机制 > 关键动机与决定 > 关系边界/权力变化 > 关键资源/身份变化 > 大事件节点
                - 每条 fact 必须是单句、客观、可检索，尽量写出明确实体与结果
                - 不要把“镜头画面 / 动作特写 / 氛围描写 / 修辞句 / 标题改写”当事实
                - 不要输出类似「一道粉色流光侵入眉心」「缩在识海最阴暗的角落」「温香软玉阁：醉生梦死」这类低价值表述
                - 更好的写法是「合欢宗秘传禁术可让施法者神魂侵入鼎炉本源并剥离其修为与天赋」
                - timeline_events 只保留关键事件节点
                - character_states 只写主要人物的重要状态变化，优先使用结构化字段（goals / injuries / relationships / emotional_state）
                - character_states 应尽量覆盖“本章结束时仍然有效”的位置、伤势、资源与目标
                - state 可作为一句话概述补充；如果结构化字段已足够，state 可以留空
                - 不确定就留空，不要猜
                - facts 最多 5 条
                """;

        String userPrompt = String.format("""
                章节ID: %s
                
                章节正文（节选）：
                %s
                
                请提取事实表更新（严格输出 JSON）：
                """,
                chapter,
                clipCanonUpdateSource(finalDraft)
        );

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            Map<String, Object> parsed = parseCanonUpdatesFromJson(projectId, response.getContent(), chapter);
            parsed = supplementCanonUpdates(projectId, chapter, finalDraft, parsed);
            parsed = postProcessCanonUpdates(projectId, chapter, parsed);
            if (isCanonUpdatesEmpty(parsed) && response.getContent() != null && !response.getContent().isBlank()) {
                log.warn("extractCanonUpdates 首次解析为空，尝试 JSON 修复: chapter={}, preview={}",
                        chapter, preview(response.getContent(), 600));
                Map<String, Object> repaired = repairCanonUpdates(projectId, chapter, response.getContent());
                repaired = supplementCanonUpdates(projectId, chapter, finalDraft, repaired);
                repaired = postProcessCanonUpdates(projectId, chapter, repaired);
                if (!isCanonUpdatesEmpty(repaired)) {
                    return repaired;
                }
            }
            return parsed;
        } catch (Exception e) {
            log.warn("extractCanonUpdates LLM 调用失败: {}", e.getMessage());
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }
    }

    private Map<String, Object> repairCanonUpdates(String projectId, String chapter, String rawContent) {
        String systemPrompt = """
                你是一位 JSON 修复器。
                你的任务不是补写内容，而是把输入文本中已经明确表达的信息整理成指定 JSON。

                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "facts": [
                    {
                      "statement": "事实陈述（一句话，简洁明确）",
                      "confidence": 0.9
                    }
                  ],
                  "timeline_events": [
                    {
                      "time": "时间描述",
                      "event": "事件描述",
                      "location": "地点",
                      "participants": ["参与者1"]
                    }
                  ],
                  "character_states": [
                    {
                      "character": "角色名",
                      "state": "一句话状态概述（可空）",
                      "goals": ["目标1"],
                      "injuries": ["伤势1"],
                      "inventory": ["物品1"],
                      "relationships": {"他人": "关系变化"},
                      "location": "所在地点",
                      "emotional_state": "情绪"
                    }
                  ]
                }

                规则：
                - 只允许整理输入里已经明确出现的信息
                - 没有就输出空数组
                - 不要解释，不要道歉，不要 markdown
                """;

        String userPrompt = String.format("""
                章节ID: %s

                原始输出：
                %s

                请将上面的原始输出整理成合法 JSON：
                """, chapter, rawContent.length() > 3000 ? rawContent.substring(0, 3000) + "..." : rawContent);

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            Map<String, Object> repaired = parseCanonUpdatesFromJson(projectId, response.getContent(), chapter);
            if (isCanonUpdatesEmpty(repaired)) {
                log.warn("repairCanonUpdates 仍为空: chapter={}, preview={}",
                        chapter, preview(response.getContent(), 600));
            }
            return repaired;
        } catch (Exception e) {
            log.warn("repairCanonUpdates 失败: chapter={}, error={}", chapter, e.getMessage());
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }
    }

    private Map<String, Object> parseCanonUpdatesFromJson(String projectId, String content, String chapter) {
        return parseCanonUpdatesFromJson(projectId, content, chapter, List.of());
    }

    private Map<String, Object> parseCanonUpdatesFromJson(String projectId,
                                                          String content,
                                                          String chapter,
                                                          Collection<String> additionalCharacters) {
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> timelineEvents = new ArrayList<>();
        List<Map<String, Object>> characterStates = new ArrayList<>();
        Set<String> factKeys = new LinkedHashSet<>();
        Set<String> timelineKeys = new LinkedHashSet<>();
        Set<String> stateKeys = new LinkedHashSet<>();
        Map<String, String> characterLookup = buildCharacterStateWhitelist(projectId, additionalCharacters);

        if (content == null || content.isBlank()) {
            return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
        }

        String json = extractJson(content);
        if (json == null) {
            log.warn("parseCanonUpdatesFromJson 未找到 JSON: chapter={}, preview={}",
                    chapter, preview(content, 600));
            return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            if (root.has("facts") && root.get("facts").isArray()) {
                for (JsonNode n : root.get("facts")) {
                    String stmt = normalizedFactStatement(n);
                    String type = n.path("type").asText("").trim();
                    String subject = firstNonBlank(
                            n.path("subject").asText("").trim(),
                            n.path("entity").asText("").trim()
                    );
                    String predicate = firstNonBlank(
                            n.path("predicate").asText("").trim(),
                            n.path("attribute").asText("").trim()
                    );
                    String object = firstNonBlank(
                            n.path("object").asText("").trim(),
                            n.path("fact").asText("").trim(),
                            n.path("value").asText("").trim()
                    );

                    if (!stmt.isBlank()) {
                        String factKey = stmt;
                        if (factKeys.add(factKey)) {
                            Map<String, Object> fact = new HashMap<>();
                            fact.put("statement", stmt);
                            fact.put("confidence", n.path("confidence").asDouble(0.9));
                            fact.put("introduced_in", chapter);
                            fact.put("source", chapter);
                            fact.put("type", type);
                            fact.put("subject", subject);
                            fact.put("predicate", predicate);
                            fact.put("object", object);
                            facts.add(fact);
                        }
                    }

                    if (!stmt.isBlank() && looksLikeTimelineEvent(type, predicate, object)) {
                        String location = n.path("location").asText("").trim();
                        if (location.isBlank() && "location".equalsIgnoreCase(type)) {
                            location = subject;
                        }
                        String timelineKey = stmt + "|" + location;
                        if (timelineKeys.add(timelineKey)) {
                            Map<String, Object> te = new HashMap<>();
                            te.put("time", n.path("time").asText(""));
                            te.put("event", stmt);
                            te.put("location", location);
                            te.put("source", chapter);
                            List<String> participants = new ArrayList<>();
                            if (!subject.isBlank()) {
                                participants.add(subject);
                            }
                            if (n.has("participants") && n.get("participants").isArray()) {
                                for (JsonNode p : n.get("participants")) {
                                    String participant = p.asText("").trim();
                                    if (!participant.isBlank() && !participants.contains(participant)) {
                                        participants.add(participant);
                                    }
                                }
                            }
                            te.put("participants", participants);
                            timelineEvents.add(te);
                        }
                    }
                }
            }

            if (root.has("timeline_events") && root.get("timeline_events").isArray()) {
                for (JsonNode n : root.get("timeline_events")) {
                    String event = n.path("event").asText("").trim();
                    if (event.isBlank()) continue;
                    String location = n.path("location").asText("");
                    String timelineKey = event + "|" + location;
                    if (!timelineKeys.add(timelineKey)) continue;
                    Map<String, Object> te = new HashMap<>();
                    te.put("time", n.path("time").asText(""));
                    te.put("event", event);
                    te.put("location", location);
                    te.put("source", chapter);
                    List<String> participants = new ArrayList<>();
                    if (n.has("participants") && n.get("participants").isArray()) {
                        for (JsonNode p : n.get("participants")) participants.add(p.asText(""));
                    }
                    te.put("participants", participants);
                    timelineEvents.add(te);
                }
            }

            if (root.has("character_states") && root.get("character_states").isArray()) {
                for (JsonNode n : root.get("character_states")) {
                    String rawCharacter = n.path("character").asText("").trim();
                    String character = resolveCanonicalCharacterName(rawCharacter, characterLookup);
                    if (character.isBlank()) continue;
                    String location = n.path("location").asText("").trim();
                    String emotionalState = n.path("emotional_state").asText("").trim();
                    List<String> goals = extractJsonStringList(n.get("goals"));
                    List<String> injuries = extractJsonStringList(n.get("injuries"));
                    List<String> inventory = extractJsonStringList(n.get("inventory"));
                    Map<String, String> relationships = extractJsonStringMap(n.get("relationships"));
                    String state = buildStructuredCharacterStateSummary(
                            n.path("state").asText("").trim(),
                            emotionalState,
                            goals,
                            injuries,
                            inventory,
                            relationships,
                            location
                    );
                    String stateKey = character + "|" + state + "|" + location + "|" + emotionalState
                            + "|" + String.join("、", goals) + "|" + String.join("、", injuries)
                            + "|" + String.join("、", inventory) + "|" + relationships;
                    if (!stateKeys.add(stateKey)) continue;
                    Map<String, Object> cs = new LinkedHashMap<>();
                    cs.put("character", character);
                    if (!state.isBlank()) {
                        cs.put("state", state);
                    }
                    if (!location.isBlank()) {
                        cs.put("location", location);
                    }
                    if (!emotionalState.isBlank()) {
                        cs.put("emotional_state", emotionalState);
                    }
                    if (!goals.isEmpty()) {
                        cs.put("goals", goals);
                    }
                    if (!injuries.isEmpty()) {
                        cs.put("injuries", injuries);
                    }
                    if (!inventory.isEmpty()) {
                        cs.put("inventory", inventory);
                    }
                    if (!relationships.isEmpty()) {
                        cs.put("relationships", relationships);
                    }
                    cs.put("last_seen", chapter);
                    characterStates.add(cs);
                }
            }
        } catch (Exception e) {
            log.warn("parseCanonUpdatesFromJson 解析失败: chapter={}, error={}, preview={}",
                    chapter, e.getMessage(), preview(json, 600));
        }

        return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> supplementCanonUpdates(String projectId,
                                                       String chapter,
                                                       String finalDraft,
                                                       Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }

        Map<String, Object> supplemented = new LinkedHashMap<>(parsed);
        List<String> focusCharacters = resolveCharacterStateFocusCharacters(projectId, finalDraft);
        List<Map<String, Object>> factCandidates = readFactCandidates(supplemented.get("facts"), chapter);
        List<Map<String, Object>> heuristicFacts = deriveHeuristicCanonFacts(chapter, finalDraft, focusCharacters, factCandidates);
        if (!heuristicFacts.isEmpty()) {
            List<Map<String, Object>> mergedFacts = new ArrayList<>(factCandidates);
            mergedFacts.addAll(heuristicFacts);
            supplemented.put("facts", mergedFacts);
        }

        List<Map<String, Object>> characterStates = new ArrayList<>();
        Object stateObj = supplemented.get("character_states");
        if (stateObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    characterStates.add(normalized);
                }
            }
        }

        if (finalDraft != null && !finalDraft.isBlank()) {
            if (characterStates.isEmpty() && !focusCharacters.isEmpty()) {
                characterStates = recoverCharacterStatesWithLlm(projectId, chapter, finalDraft, focusCharacters);
            }
            List<Map<String, Object>> heuristicStates = deriveHeuristicCharacterStates(chapter, finalDraft, focusCharacters);
            if (characterStates.isEmpty()) {
                characterStates = heuristicStates;
            } else if (!heuristicStates.isEmpty()) {
                characterStates = mergeCharacterStates(characterStates, heuristicStates, chapter);
            }
            if (!characterStates.isEmpty()) {
                supplemented.put("character_states", characterStates);
            }
        }
        return supplemented;
    }

    private List<Map<String, Object>> mergeCharacterStates(List<Map<String, Object>> existingStates,
                                                           List<Map<String, Object>> heuristicStates,
                                                           String chapter) {
        if (existingStates == null || existingStates.isEmpty()) {
            return heuristicStates == null ? List.of() : heuristicStates;
        }
        if (heuristicStates == null || heuristicStates.isEmpty()) {
            return existingStates;
        }

        Map<String, Map<String, Object>> heuristicByCharacter = new LinkedHashMap<>();
        for (Map<String, Object> heuristic : heuristicStates) {
            String character = compactInlineText(String.valueOf(heuristic.getOrDefault("character", "")));
            if (!character.isBlank()) {
                heuristicByCharacter.put(character, heuristic);
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map<String, Object> existing : existingStates) {
            String character = compactInlineText(String.valueOf(existing.getOrDefault("character", "")));
            Map<String, Object> heuristic = heuristicByCharacter.get(character);
            if (heuristic == null) {
                merged.add(existing);
                continue;
            }
            used.add(character);
            merged.add(mergeSingleCharacterState(existing, heuristic, chapter));
        }
        for (Map<String, Object> heuristic : heuristicStates) {
            String character = compactInlineText(String.valueOf(heuristic.getOrDefault("character", "")));
            if (!character.isBlank() && !used.contains(character)) {
                merged.add(heuristic);
            }
        }
        return merged;
    }

    private Map<String, Object> mergeSingleCharacterState(Map<String, Object> existing,
                                                          Map<String, Object> heuristic,
                                                          String chapter) {
        Map<String, Object> merged = new LinkedHashMap<>();
        String character = firstNonBlank(
                compactInlineText(String.valueOf(heuristic.getOrDefault("character", ""))),
                compactInlineText(String.valueOf(existing.getOrDefault("character", "")))
        );
        if (!character.isBlank()) {
            merged.put("character", character);
        }

        String location = preferMoreSpecificText(
                compactInlineText(String.valueOf(existing.getOrDefault("location", ""))),
                compactInlineText(String.valueOf(heuristic.getOrDefault("location", "")))
        );
        String emotion = preferMoreSpecificText(
                compactInlineText(String.valueOf(existing.getOrDefault("emotional_state", existing.getOrDefault("emotion", "")))),
                compactInlineText(String.valueOf(heuristic.getOrDefault("emotional_state", heuristic.getOrDefault("emotion", ""))))
        );
        List<String> goals = mergeStateList(existing.get("goals"), heuristic.get("goals"), true);
        List<String> injuries = mergeStateList(existing.get("injuries"), heuristic.get("injuries"), false);
        List<String> inventory = mergeStateList(existing.get("inventory"), heuristic.get("inventory"), true);
        Map<String, String> relationships = mergeStateMap(existing.get("relationships"), heuristic.get("relationships"));
        String state = buildStructuredCharacterStateSummary("", emotion, goals, injuries, inventory, relationships, location);

        if (!state.isBlank()) {
            merged.put("state", state);
        }
        if (!location.isBlank()) {
            merged.put("location", location);
        }
        if (!emotion.isBlank()) {
            merged.put("emotional_state", emotion);
        }
        if (!goals.isEmpty()) {
            merged.put("goals", goals);
        }
        if (!injuries.isEmpty()) {
            merged.put("injuries", injuries);
        }
        if (!inventory.isEmpty()) {
            merged.put("inventory", inventory);
        }
        if (!relationships.isEmpty()) {
            merged.put("relationships", relationships);
        }
        merged.put("last_seen", firstNonBlank(
                compactInlineText(String.valueOf(heuristic.getOrDefault("last_seen", ""))),
                compactInlineText(String.valueOf(existing.getOrDefault("last_seen", ""))),
                chapter
        ));
        return merged;
    }

    private List<String> mergeStateList(Object existingRaw, Object heuristicRaw, boolean heuristicFirst) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        List<String> existing = normalizeStateList(existingRaw);
        List<String> heuristic = normalizeStateList(heuristicRaw);
        List<String> first = heuristicFirst ? heuristic : existing;
        List<String> second = heuristicFirst ? existing : heuristic;
        for (String item : first) {
            if (!item.isBlank()) {
                values.add(item);
            }
        }
        for (String item : second) {
            if (!item.isBlank()) {
                values.add(item);
            }
        }
        return new ArrayList<>(values);
    }

    private Map<String, String> mergeStateMap(Object existingRaw, Object heuristicRaw) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(normalizeStateMap(existingRaw));
        normalizeStateMap(heuristicRaw).forEach(merged::putIfAbsent);
        return merged;
    }

    private List<String> normalizeStateList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String cleaned = compactInlineText(String.valueOf(item == null ? "" : item));
                if (!cleaned.isBlank() && !values.contains(cleaned)) {
                    values.add(cleaned);
                }
            }
            return values;
        }
        String cleaned = compactInlineText(String.valueOf(raw == null ? "" : raw));
        if (!cleaned.isBlank()) {
            values.add(cleaned);
        }
        return values;
    }

    private Map<String, String> normalizeStateMap(Object raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = compactInlineText(String.valueOf(entry.getKey() == null ? "" : entry.getKey()));
                String value = compactInlineText(String.valueOf(entry.getValue() == null ? "" : entry.getValue()));
                if (!key.isBlank() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
        }
        return values;
    }

    private String preferMoreSpecificText(String existing, String heuristic) {
        String left = compactInlineText(existing);
        String right = compactInlineText(heuristic);
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        if (isMoreSpecificStateText(right, left)) {
            return right;
        }
        return left;
    }

    private boolean isMoreSpecificStateText(String candidate, String baseline) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (baseline == null || baseline.isBlank()) {
            return true;
        }
        int candidateScore = scoreStateSpecificity(candidate);
        int baselineScore = scoreStateSpecificity(baseline);
        if (candidateScore != baselineScore) {
            return candidateScore > baselineScore;
        }
        return candidate.length() > baseline.length();
    }

    private int scoreStateSpecificity(String text) {
        int score = compactInlineText(text).length();
        if (containsAnyToken(text, FACT_DISCOVERY_HINTS)) {
            score += 30;
        }
        if (containsAnyToken(text, List.of("古洞", "洞室", "石缝", "阵纹", "太阴", "灰白雾气", "阴寒气机", "木牌"))) {
            score += 20;
        }
        if (containsAnyToken(text, List.of("补入少量清水", "内伤闷堵", "传承", "修炼图"))) {
            score += 15;
        }
        return score;
    }

    private List<Map<String, Object>> readFactCandidates(Object rawFactsObj, String chapter) {
        List<Map<String, Object>> rawFacts = new ArrayList<>();
        if (rawFactsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    normalized.putIfAbsent("introduced_in", chapter);
                    normalized.putIfAbsent("source", chapter);
                    rawFacts.add(normalized);
                } else if (item instanceof String text && !text.isBlank()) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("statement", text.trim());
                    normalized.put("confidence", 0.9d);
                    normalized.put("introduced_in", chapter);
                    normalized.put("source", chapter);
                    rawFacts.add(normalized);
                }
            }
        }
        return rawFacts;
    }

    private List<String> resolveCharacterStateFocusCharacters(String projectId, String finalDraft) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (finalDraft == null || finalDraft.isBlank()) {
            return List.of();
        }
        names.addAll(extractMentionsFromTexts(projectId, List.of(finalDraft), "character"));
        if (!names.isEmpty()) {
            return names.stream().limit(3).toList();
        }

        Map<String, CharacterProposalCandidate> candidates = extractCharacterCandidates(finalDraft);
        candidates.values().stream()
                .filter(this::passesHighConfidenceCharacterThreshold)
                .sorted(Comparator.comparingDouble(CharacterProposalCandidate::score).reversed())
                .map(CharacterProposalCandidate::name)
                .filter(name -> name != null && !name.isBlank())
                .limit(3)
                .forEach(names::add);
        return new ArrayList<>(names);
    }

    private List<Map<String, Object>> recoverCharacterStatesWithLlm(String projectId,
                                                                    String chapter,
                                                                    String finalDraft,
                                                                    List<String> focusCharacters) {
        if (focusCharacters == null || focusCharacters.isEmpty()) {
            return List.of();
        }

        String systemPrompt = """
                你是一位“角色状态补抽器”。
                你的任务不是重做整章 facts，而是只为指定角色补齐“本章结束时仍然有效”的角色状态。

                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "character_states": [
                    {
                      "character": "角色名",
                      "state": "一句话状态概述（可空）",
                      "goals": ["目标1"],
                      "injuries": ["伤势1"],
                      "inventory": ["物品1"],
                      "relationships": {"他人": "关系变化"},
                      "location": "所在地点",
                      "emotional_state": "情绪"
                    }
                  ]
                }

                规则：
                - 只抽取以下指定角色：%s
                - 只保留本章结束时仍然有效、可延续到下一章的状态
                - 优先输出结构化字段：goals / injuries / inventory / location / emotional_state
                - 若正文无明确证据，不要猜；可留空或输出 []
                """.formatted(String.join("、", focusCharacters));

        String userPrompt = String.format("""
                章节ID: %s
                目标角色: %s

                章节正文（节选）：
                %s

                请补齐 character_states（严格输出 JSON）：
                """,
                chapter,
                String.join("、", focusCharacters),
                clipCharacterStateSource(finalDraft)
        );

        try {
            LlmResponse response = callLlm(buildMessages(systemPrompt, userPrompt, List.of()));
            Map<String, Object> parsed = parseCanonUpdatesFromJson(projectId, response.getContent(), chapter, focusCharacters);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> states = (List<Map<String, Object>>) parsed.getOrDefault("character_states", List.of());
            return states;
        } catch (Exception e) {
            log.warn("recoverCharacterStatesWithLlm 失败: chapter={}, error={}", chapter, e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> deriveHeuristicCharacterStates(String chapter,
                                                                     String finalDraft,
                                                                     List<String> focusCharacters) {
        if (finalDraft == null || finalDraft.isBlank() || focusCharacters == null || focusCharacters.isEmpty()) {
            return List.of();
        }
        String primary = focusCharacters.get(0);
        if (primary == null || primary.isBlank() || !finalDraft.contains(primary)) {
            return List.of();
        }

        List<String> injuries = new ArrayList<>();
        if (containsAll(finalDraft, "右腿", "伤") || containsAll(finalDraft, "右腿", "痛") || containsAll(finalDraft, "右腿", "疼")) {
            injuries.add("右腿受伤");
        }
        if (containsAll(finalDraft, "后背", "伤") || containsAll(finalDraft, "后背", "疼") || containsAll(finalDraft, "后背", "擦")) {
            injuries.add("后背重伤");
        }
        if (containsAll(finalDraft, "肋下", "伤") || containsAll(finalDraft, "肋下", "疼") || containsAll(finalDraft, "肋下", "痛")) {
            injuries.add("肋下内伤");
        }
        if (containsAnyToken(finalDraft, List.of("咳血", "喉头一甜", "牵动内伤"))) {
            addUnique(injuries, "强行运气会牵动内伤");
        }

        List<String> inventory = new ArrayList<>();
        if (containsAll(finalDraft, "法器", "不见") || containsAll(finalDraft, "法器", "遗失")) {
            inventory.add("随身法器遗失");
        }
        if (containsAll(finalDraft, "木牌", "磕裂") || containsAll(finalDraft, "木牌", "磕")) {
            inventory.add("磕裂木牌");
        }
        if (containsAnyToken(finalDraft, List.of("木牌忽然", "木牌轻响", "木牌忽然“啪”地轻响"))) {
            addUnique(inventory, "发生异动的木牌");
        }
        if (containsAnyToken(finalDraft, List.of("少量碎干粮", "两小块", "干粮"))) {
            addUnique(inventory, "少量碎干粮");
        }
        if (containsAnyToken(finalDraft, List.of("把空了大半的水囊慢慢灌了一点", "水囊慢慢灌了一点", "刚灌的那点水", "给水囊补了一点水", "补了一点水"))) {
            addUnique(inventory, "补入少量清水的水囊");
        } else if (containsAll(finalDraft, "水囊", "几乎见底") || containsAll(finalDraft, "水囊", "最后一点")) {
            addUnique(inventory, "几乎见底的水囊");
        }
        if (containsAnyToken(finalDraft, List.of("残片", "“阴”字", "那截残片小心塞进怀里"))) {
            addUnique(inventory, "刻有“阴”字的残片");
        }
        if (containsAnyToken(finalDraft, List.of("尖石", "把尖石横在身前", "尖石倒是锋利"))) {
            addUnique(inventory, "临时防身尖石");
        }

        List<String> goals = new ArrayList<>();
        if (containsAnyToken(finalDraft, List.of("太阴", "壁刻", "人形图", "修炼图", "传承", "引气", "封脉"))) {
            goals.add("弄清太阴壁刻与人形修炼图的来历");
        }
        if (containsAnyToken(finalDraft, List.of("阵纹", "灰白雾气", "禁制", "嗡地轻震", "嗡鸣"))) {
            addUnique(goals, "查明古洞阵纹与灰白雾气是否仍可触发");
        }
        if (containsAnyToken(finalDraft, List.of("阴寒气机", "寒意", "闷堵", "冲开了一丝", "压住了一角"))) {
            addUnique(goals, "确认阴寒气机对内伤的作用与代价");
        }
        if (containsAll(finalDraft, "决定", "沿崖壁探索") || containsAll(finalDraft, "继续", "沿崖壁")) {
            goals.add("沿崖壁继续寻找出路");
        }
        if (goals.isEmpty() && containsAnyToken(finalDraft, List.of("微光", "石面", "门框", "断碑"))) {
            addUnique(goals, "确认谷底异常石面与微光的来源");
        }
        if (containsAnyToken(finalDraft, List.of("闷堵", "压住了一角", "冲开了一丝", "那股卡了许久的闷堵竟真被冲开了一丝"))) {
            addUnique(injuries, "内伤闷堵开始松动");
        }

        String location = extractHeuristicLocation(finalDraft);
        String emotionalState = extractHeuristicEmotion(finalDraft, injuries, goals);
        String state = buildStructuredCharacterStateSummary(
                "",
                emotionalState,
                goals,
                injuries,
                inventory,
                Map.of(),
                location
        );

        if (state.isBlank() && location.isBlank() && injuries.isEmpty() && inventory.isEmpty() && goals.isEmpty()) {
            return List.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("character", primary);
        if (!state.isBlank()) {
            result.put("state", state);
        }
        if (!location.isBlank()) {
            result.put("location", location);
        }
        if (!emotionalState.isBlank()) {
            result.put("emotional_state", emotionalState);
        }
        if (!goals.isEmpty()) {
            result.put("goals", goals);
        }
        if (!injuries.isEmpty()) {
            result.put("injuries", injuries);
        }
        if (!inventory.isEmpty()) {
            result.put("inventory", inventory);
        }
        result.put("last_seen", chapter);
        return List.of(result);
    }

    private List<Map<String, Object>> deriveHeuristicCanonFacts(String chapter,
                                                                String finalDraft,
                                                                List<String> focusCharacters,
                                                                List<Map<String, Object>> existingFacts) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> fact : existingFacts == null ? List.<Map<String, Object>>of() : existingFacts) {
            String statement = cleanFactStatement(String.valueOf(fact.getOrDefault("statement", fact.getOrDefault("content", ""))));
            String normalized = normalizeFactDedupKey(statement);
            if (!normalized.isBlank()) {
                seen.add(normalized);
            }
        }

        String subject = focusCharacters == null || focusCharacters.isEmpty() ? "" : focusCharacters.get(0);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean hasDiscoveryArc = containsAnyToken(finalDraft, List.of(
                "古洞", "洞室", "阵纹", "太阴", "壁刻", "人形图", "修炼图",
                "灰白雾气", "积水", "木牌忽然", "木牌轻响", "阴寒气机"
        ));

        if (containsAnyToken(finalDraft, List.of("废弃已久的洞室", "废弃古洞", "洞室", "古洞"))
                && containsAnyToken(finalDraft, List.of("石台", "断裂的石片", "断裂石片", "白光"))) {
            addHeuristicFact(results, seen, chapter, "发黑石面后的石缝连接一处废弃古洞，洞中残留石台与断裂石片", 0.93d);
        }
        if (containsAnyToken(finalDraft, List.of("阵纹", "纹路极细", "断裂痕迹", "禁制崩坏", "禁制"))) {
            addHeuristicFact(results, seen, chapter, "古洞地面残留受损阵纹，疑似旧日禁制或阵法残迹", 0.92d);
        }
        if (containsAnyToken(finalDraft, List.of("积水", "腹中没有翻腾", "水囊慢慢灌了一点", "把空了大半的水囊慢慢灌了一点"))) {
            addHeuristicFact(results, seen, chapter, "古洞深处的积水可少量饮用，王方得以补充少量饮水", 0.9d);
        }
        if (containsAnyToken(finalDraft, List.of("太阴", "壁刻", "人形图", "修炼图", "引气", "封脉", "寒入骨"))) {
            addHeuristicFact(results, seen, chapter, "古洞石壁残留与“太阴”相关的壁刻和人形修炼图，疑似古修传承线索", 0.95d);
        }
        if (containsAnyToken(finalDraft, List.of("灰白雾气", "嗡地轻震", "太阴”二字", "太阴”二字", "太阴”二字", "太阴\"二字", "亮起来"))
                || (containsAnyToken(finalDraft, List.of("阵纹")) && containsAnyToken(finalDraft, List.of("嗡", "雾气", "亮起来")))) {
            addHeuristicFact(results, seen, chapter, "残破阵纹被触动后仍能引发嗡鸣与灰白雾气，古洞禁制尚未彻底失效", 0.94d);
        }
        if (containsAnyToken(finalDraft, List.of("压住了一角", "冲开了一丝", "阴寒气机", "那股卡了许久的闷堵竟真被冲开了一丝"))) {
            addHeuristicFact(results, seen, chapter, "古洞中的阴寒气机可压制并部分冲开王方体内滞涩伤势", 0.93d);
        }
        if (containsAnyToken(finalDraft, List.of("木牌忽然", "木牌轻响", "木牌忽然“啪”地轻响", "字迹，正一点点亮起来"))) {
            addHeuristicFact(results, seen, chapter, "太阴字迹亮起时，王方怀中的木牌同步发生异动", 0.89d);
        }

        boolean needsCarryoverFacts = results.size() < 3 && !hasDiscoveryArc;
        if (!subject.isBlank()) {
            if (needsCarryoverFacts && (containsAnyToken(finalDraft, List.of("坠崖", "坠下", "坠落", "跌下", "掉下")) || containsAnyToken(finalDraft, List.of("求援无果", "高耸崖壁", "爬不上去")))
                    && containsAnyToken(finalDraft, List.of("谷底", "崖下", "崖底"))) {
                addHeuristicFact(results, seen, chapter, subject + "坠崖后受困谷底，短期内无法与上方环境重新接续", 0.82d);
            }
            if (needsCarryoverFacts && containsAnyToken(finalDraft, List.of("牵动内伤", "强行运气", "运功疗伤", "一尝试运气便"))) {
                addHeuristicFact(results, seen, chapter, subject + "强行运气会牵动内伤，当前无法依靠运功疗伤", 0.88d);
            }
            if (needsCarryoverFacts && (containsAll(finalDraft, "法器", "遗失") || containsAll(finalDraft, "法器", "不见"))) {
                addHeuristicFact(results, seen, chapter, subject + "随身法器遗失，谷底求生只能依赖残余物资", 0.86d);
            }
            if (needsCarryoverFacts && (containsAll(finalDraft, "水囊", "见底") || containsAnyToken(finalDraft, List.of("少量碎干粮", "只剩", "残余物资")))) {
                addHeuristicFact(results, seen, chapter, subject + "随身水囊几乎见底且补给有限，当前求生资源极度匮乏", 0.80d);
            }
        }
        if (needsCarryoverFacts && containsAnyToken(finalDraft, List.of("人工修整", "人工痕迹", "门框", "断碑", "削过", "棱线"))
                && containsAnyToken(finalDraft, List.of("石面", "岩面", "崖壁", "狭缝"))) {
            addHeuristicFact(results, seen, chapter, "谷底崖壁附近留有人工修整痕迹，附近存在旧时人工结构", 0.84d);
        }
        if (needsCarryoverFacts && containsAnyToken(finalDraft, List.of("拖擦声", "拖擦异响", "蹭过来", "一寸一寸地蹭"))) {
            addHeuristicFact(results, seen, chapter, "谷底雾中持续逼近的拖擦异响表明附近存在未知危险", 0.76d);
        }
        if (needsCarryoverFacts && containsAnyToken(finalDraft, List.of("幽冷", "微青", "青光", "微光"))
                && containsAnyToken(finalDraft, List.of("狭缝", "石面", "发黑"))) {
            addHeuristicFact(results, seen, chapter, "发黑石面狭缝深处持续渗出幽冷青光，谷底存在异常源头", 0.74d);
        }
        return results;
    }

    private void addHeuristicFact(List<Map<String, Object>> results,
                                  Set<String> seen,
                                  String chapter,
                                  String statement,
                                  double confidence) {
        String cleaned = cleanFactStatement(statement);
        String normalized = normalizeFactDedupKey(cleaned);
        if (cleaned.isBlank() || normalized.isBlank() || (seen != null && !seen.add(normalized))) {
            return;
        }
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("statement", cleaned);
        fact.put("confidence", confidence);
        fact.put("introduced_in", chapter);
        fact.put("source", chapter);
        results.add(fact);
    }

    private String extractHeuristicLocation(String finalDraft) {
        List<Pattern> patterns = List.of(
                Pattern.compile("这是间([^，。；\\n]{2,24}洞室)"),
                Pattern.compile("(石缝[^，。；\\n]{0,12}洞室)"),
                Pattern.compile("(废弃[^，。；\\n]{0,8}古洞)"),
                Pattern.compile("身处([^，。；\\n]{3,28})"),
                Pattern.compile("发现自己身处([^，。；\\n]{3,28})"),
                Pattern.compile("在([^，。；\\n]{3,20}谷底)"),
                Pattern.compile("谷底[^，。；\\n]{0,12}(?:崖壁|石面|深处)")
        );
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(finalDraft);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String cleaned = compactInlineText(value);
                if (!cleaned.isBlank()) {
                    return truncateInline(cleaned, 28);
                }
            }
        }
        if (containsAnyToken(finalDraft, List.of("洞室", "古洞", "石缝"))) {
            return "谷底石缝后的废弃古洞";
        }
        if (finalDraft.contains("谷底")) {
            return "浓雾笼罩的谷底";
        }
        return "";
    }

    private String extractHeuristicEmotion(String finalDraft,
                                           List<String> injuries,
                                           List<String> goals) {
        boolean hasFear = containsAnyToken(finalDraft, List.of("不敢", "发紧", "冷汗", "惊", "吓"));
        boolean hasCalm = containsAnyToken(finalDraft, List.of("逼着自己", "先不去管", "压住", "稳住", "清醒了些"));
        boolean hasAlert = containsAnyToken(finalDraft, List.of("警惕", "攥紧", "不安全", "不敢多停", "继续往前"));
        boolean hasProbe = containsAnyToken(finalDraft, List.of("没敢直接照着那图去试", "谨慎", "试着靠近", "围着洞室慢慢转了一圈"));
        if (hasProbe && (hasFear || !injuries.isEmpty()) && !goals.isEmpty()) {
            return "负伤中谨慎试探古洞异象，同时保持高度戒备";
        }
        if ((hasFear || !injuries.isEmpty()) && (hasCalm || !goals.isEmpty()) && hasAlert) {
            return "惊惧中强迫自己保持冷静与警惕";
        }
        if ((hasFear || !injuries.isEmpty()) && (hasCalm || !goals.isEmpty())) {
            return "受伤且紧张，但仍强迫自己冷静";
        }
        if (hasAlert) {
            return "高度警惕";
        }
        return "";
    }

    private Map<String, String> buildCharacterStateWhitelist(String projectId, Collection<String> additionalNames) {
        Map<String, String> lookup = new LinkedHashMap<>();
        if (additionalNames != null) {
            for (String additional : additionalNames) {
                addCharacterLookupKey(lookup, additional, additional);
            }
        }
        if (projectId == null || projectId.isBlank()) {
            return lookup;
        }
        try {
            for (String cardName : cardStorage.listCharacterCards(projectId)) {
                if (cardName == null || cardName.isBlank()) {
                    continue;
                }
                String canonical = cardName.trim();
                addCharacterLookupKey(lookup, canonical, canonical);
                cardStorage.getCharacterCard(projectId, canonical).ifPresent(card -> {
                    if (card.getAliases() != null) {
                        for (String alias : card.getAliases()) {
                            addCharacterLookupKey(lookup, alias, canonical);
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.debug("buildCharacterStateWhitelist 失败: {}", e.getMessage());
        }
        return lookup;
    }

    private void addCharacterLookupKey(Map<String, String> lookup, String rawKey, String canonical) {
        String key = rawKey == null ? "" : rawKey.trim();
        String target = canonical == null ? "" : canonical.trim();
        if (key.isBlank() || target.isBlank()) {
            return;
        }
        lookup.putIfAbsent(key, target);
        lookup.putIfAbsent(key.toLowerCase(Locale.ROOT), target);
    }

    private String resolveCanonicalCharacterName(String rawSubject, Map<String, String> lookup) {
        String subject = rawSubject == null ? "" : rawSubject.trim();
        if (subject.isBlank()) {
            return "";
        }
        if (!isPlausibleCharacterStateSubject(subject)) {
            return "";
        }
        if (lookup == null || lookup.isEmpty()) {
            return subject;
        }
        String canonical = lookup.get(subject);
        if (canonical != null && !canonical.isBlank()) {
            return canonical;
        }
        canonical = lookup.get(subject.toLowerCase(Locale.ROOT));
        if (canonical != null && !canonical.isBlank()) {
            return canonical;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postProcessCanonUpdates(String projectId, String chapter, Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }

        List<Map<String, Object>> rawFacts = readFactCandidates(parsed.get("facts"), chapter);
        AdaptiveHintProfile hintProfile = resolveAdaptiveHintProfile(projectId, chapter, rawFacts, List.of());

        List<String> existingStatements = new ArrayList<>();
        try {
            for (Fact fact : canonStorage.listFacts(projectId)) {
                String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
                if (chapter != null && !chapter.isBlank() && chapter.equalsIgnoreCase(factChapter)) {
                    continue;
                }
                String statement = firstNonBlank(
                        fact.getStatement() != null ? fact.getStatement().trim() : "",
                        fact.getContent() != null ? fact.getContent().trim() : ""
                );
                if (!statement.isBlank()) {
                    existingStatements.add(statement);
                }
            }
        } catch (Exception e) {
            log.debug("postProcessCanonUpdates 读取既有 facts 失败: {}", e.getMessage());
        }

        List<Map<String, Object>> selectedFacts = selectHighValueCanonFacts(
                rawFacts,
                existingStatements,
                MAX_CANON_FACTS,
                chapter,
                hintProfile
        );
        if (!rawFacts.isEmpty()) {
            log.info("Canon facts filtered: chapter={}, raw={}, selected={}, sample={}",
                    chapter,
                    rawFacts.size(),
                    selectedFacts.size(),
                    selectedFacts.stream()
                            .map(item -> String.valueOf(item.getOrDefault("statement", "")))
                            .filter(text -> text != null && !text.isBlank())
                            .limit(3)
                            .toList());
        }

        Map<String, Object> result = new LinkedHashMap<>(parsed);
        result.put("facts", selectedFacts);
        return result;
    }

    private List<Map<String, Object>> selectHighValueCanonFacts(List<Map<String, Object>> candidates,
                                                                List<String> existingStatements,
                                                                int limit,
                                                                String chapter,
                                                                AdaptiveHintProfile hintProfile) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String statement : existingStatements == null ? List.<String>of() : existingStatements) {
            String normalized = normalizeFactDedupKey(statement);
            if (!normalized.isBlank()) {
                seen.add(normalized);
            }
        }

        List<CanonFactCandidate> scored = new ArrayList<>();
        boolean hasDiscoveryCandidate = false;
        for (Map<String, Object> candidate : candidates) {
            String statement = cleanFactStatement(firstNonBlank(
                    String.valueOf(candidate == null ? "" : candidate.getOrDefault("statement", "")).trim(),
                    String.valueOf(candidate == null ? "" : candidate.getOrDefault("content", "")).trim(),
                    String.valueOf(candidate == null ? "" : candidate.getOrDefault("fact", "")).trim(),
                    String.valueOf(candidate == null ? "" : candidate.getOrDefault("text", "")).trim()
            ));
            if (!statement.isBlank() && containsFactDiscoveryHint(statement, hintProfile)) {
                hasDiscoveryCandidate = true;
                break;
            }
        }
        for (Map<String, Object> candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String type = String.valueOf(candidate.getOrDefault("type", "")).trim();
            String predicate = String.valueOf(candidate.getOrDefault("predicate", "")).trim();
            String statement = cleanFactStatement(firstNonBlank(
                    String.valueOf(candidate.getOrDefault("statement", "")).trim(),
                    String.valueOf(candidate.getOrDefault("content", "")).trim(),
                    String.valueOf(candidate.getOrDefault("fact", "")).trim(),
                    String.valueOf(candidate.getOrDefault("text", "")).trim()
            ));
            if (statement.isBlank() || statement.length() < 6) {
                continue;
            }
            String normalized = normalizeFactDedupKey(statement);
            if (normalized.isBlank() || seen.contains(normalized)) {
                continue;
            }
            seen.add(normalized);

            double confidence = clampConfidence(candidate.get("confidence"));
            double score = scoreFactStatement(statement, hintProfile);
            boolean simpleRelation = isSimpleRelationFact(statement);
            boolean durable = looksLikeDurableCanonFact(statement, hintProfile);
            boolean survivalDurable = looksLikeSurvivalCanonFact(statement, hintProfile);
            boolean discoveryDurable = containsFactDiscoveryHint(statement, hintProfile);
            boolean durableStateLike = containsFactDensityHint(statement, hintProfile)
                    || containsFactMechanismHint(statement, hintProfile)
                    || statement.contains("成为")
                    || statement.contains("不再")
                    || statement.contains("失去")
                    || statement.contains("获得")
                    || statement.contains("死亡");
            boolean lowValue = looksLikeLowValueSceneFact(statement, hintProfile)
                    || looksLikeDecorativeLabelFact(statement)
                    || looksLikeMomentaryActionFact(statement)
                    || looksLikeUnnamedGenericActorFact(statement)
                    || looksLikeTransientWorklogFact(statement)
                    || (containsAny(statement, FACT_UNCERTAIN_HINTS) && !containsFactMechanismHint(statement, hintProfile))
                    || ((looksLikeCharacterState(type, predicate) || looksLikeLocationPredicate(predicate)) && !durableStateLike)
                    || (statement.contains("状态") && !durableStateLike);
            if (discoveryDurable) {
                score += 0.75d;
            }
            if (hasDiscoveryCandidate && containsAny(statement, List.of("法器遗失", "水囊", "求生资源", "拖擦异响", "受困谷底", "残余物资"))) {
                score -= 0.55d;
            }
            scored.add(new CanonFactCandidate(statement, confidence, score + (survivalDurable ? 0.35d : 0.0d), simpleRelation, lowValue && !durable && !survivalDurable && !discoveryDurable));
        }

        scored.sort(Comparator
                .comparingDouble(CanonFactCandidate::score).reversed()
                .thenComparing((CanonFactCandidate item) -> item.statement().length(), Comparator.reverseOrder())
                .thenComparing(CanonFactCandidate::statement));

        List<Map<String, Object>> selected = new ArrayList<>();
        for (CanonFactCandidate candidate : scored) {
            if (!looksLikeDurableCanonFact(candidate.statement(), hintProfile)
                    && !looksLikeSurvivalCanonFact(candidate.statement(), hintProfile)) {
                continue;
            }
            if (candidate.simpleRelation()) {
                continue;
            }
            if (candidate.lowValue() && candidate.score() < 1.2d) {
                continue;
            }
            if (candidate.score() < 0.45d) {
                continue;
            }
            selected.add(toCanonFactMap(candidate, chapter));
            if (selected.size() >= limit) {
                return ensureDiscoveryCoverage(selected, scored, chapter, limit, hintProfile);
            }
        }

        if (selected.isEmpty()) {
            for (CanonFactCandidate candidate : scored) {
                if (!looksLikeDurableCanonFact(candidate.statement(), hintProfile)
                        && !looksLikeSurvivalCanonFact(candidate.statement(), hintProfile)) {
                    continue;
                }
                if (candidate.lowValue() && candidate.score() < 1.0d) {
                    continue;
                }
                if (candidate.score() < 0.25d) {
                    continue;
                }
                selected.add(toCanonFactMap(candidate, chapter));
                if (selected.size() >= Math.min(2, limit)) {
                    return ensureDiscoveryCoverage(selected, scored, chapter, limit, hintProfile);
                }
            }
        }

        int relationUsed = 0;
        for (CanonFactCandidate candidate : scored) {
            if (!looksLikeDurableCanonFact(candidate.statement(), hintProfile)) {
                continue;
            }
            if (!candidate.simpleRelation()) {
                continue;
            }
            if (candidate.score() < 1.0d) {
                continue;
            }
            selected.add(toCanonFactMap(candidate, chapter));
            relationUsed++;
            if (selected.size() >= limit || relationUsed >= 1) {
                break;
            }
        }
        return ensureDiscoveryCoverage(selected, scored, chapter, limit, hintProfile);
    }

    private Map<String, Object> toCanonFactMap(CanonFactCandidate candidate, String chapter) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("statement", candidate.statement());
        fact.put("confidence", candidate.confidence());
        fact.put("introduced_in", chapter);
        fact.put("source", chapter);
        return fact;
    }

    private List<Map<String, Object>> ensureDiscoveryCoverage(List<Map<String, Object>> selected,
                                                              List<CanonFactCandidate> scored,
                                                              String chapter,
                                                              int limit,
                                                              AdaptiveHintProfile hintProfile) {
        if (selected == null || selected.isEmpty() || scored == null || scored.isEmpty() || limit <= 0) {
            return selected == null ? List.of() : selected;
        }
        List<Map<String, Object>> adjusted = new ArrayList<>(selected);
        adjusted = ensureDiscoveryCoverageForTokens(
                adjusted,
                scored,
                chapter,
                limit,
                preferredDiscoveryCoverageTokens(hintProfile),
                hintProfile
        );
        return adjusted;
    }

    private List<Map<String, Object>> ensureDiscoveryCoverageForTokens(List<Map<String, Object>> selected,
                                                                       List<CanonFactCandidate> scored,
                                                                       String chapter,
                                                                       int limit,
                                                                       List<String> tokens,
                                                                       AdaptiveHintProfile hintProfile) {
        if (tokens == null || tokens.isEmpty()) {
            return selected;
        }
        boolean alreadyCovered = selected.stream()
                .map(item -> cleanFactStatement(String.valueOf(item.getOrDefault("statement", ""))))
                .anyMatch(statement -> containsAny(statement, tokens));
        if (alreadyCovered) {
            return selected;
        }

        CanonFactCandidate replacement = null;
        for (CanonFactCandidate candidate : scored) {
            if (!containsAny(candidate.statement(), tokens)) {
                continue;
            }
            if (!looksLikeDurableCanonFact(candidate.statement(), hintProfile)
                    && !looksLikeSurvivalCanonFact(candidate.statement(), hintProfile)) {
                continue;
            }
            if (candidate.lowValue() && candidate.score() < 1.2d) {
                continue;
            }
            replacement = candidate;
            break;
        }
        if (replacement == null) {
            return selected;
        }

        String replacementKey = normalizeFactDedupKey(replacement.statement());
        boolean alreadyPresent = selected.stream()
                .map(item -> normalizeFactDedupKey(String.valueOf(item.getOrDefault("statement", ""))))
                .anyMatch(normalized -> normalized.equals(replacementKey));
        if (alreadyPresent) {
            return selected;
        }

        if (selected.size() < limit) {
            List<Map<String, Object>> expanded = new ArrayList<>(selected);
            expanded.add(toCanonFactMap(replacement, chapter));
            return expanded;
        }

        Map<String, Double> scoreByStatement = new LinkedHashMap<>();
        for (CanonFactCandidate candidate : scored) {
            scoreByStatement.putIfAbsent(normalizeFactDedupKey(candidate.statement()), candidate.score());
        }
        int removeIndex = -1;
        double weakestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < selected.size(); i++) {
            String statement = cleanFactStatement(String.valueOf(selected.get(i).getOrDefault("statement", "")));
            if (containsAny(statement, tokens)) {
                continue;
            }
            double score = scoreByStatement.getOrDefault(normalizeFactDedupKey(statement), 0.0d);
            if (score < weakestScore) {
                weakestScore = score;
                removeIndex = i;
            }
        }
        if (removeIndex >= 0 && replacement.score() >= weakestScore - 0.25d) {
            List<Map<String, Object>> replaced = new ArrayList<>(selected);
            replaced.set(removeIndex, toCanonFactMap(replacement, chapter));
            return replaced;
        }
        return selected;
    }

    private AdaptiveHintProfile resolveAdaptiveHintProfile(String projectId,
                                                           String chapter,
                                                           List<Map<String, Object>> rawFacts,
                                                           Collection<String> extraTexts) {
        List<String> overlays = new ArrayList<>();
        if (rawFacts != null) {
            for (Map<String, Object> fact : rawFacts) {
                if (fact == null || fact.isEmpty()) {
                    continue;
                }
                String statement = cleanFactStatement(firstNonBlank(
                        String.valueOf(fact.getOrDefault("statement", "")).trim(),
                        String.valueOf(fact.getOrDefault("content", "")).trim(),
                        String.valueOf(fact.getOrDefault("fact", "")).trim(),
                        String.valueOf(fact.getOrDefault("text", "")).trim()
                ));
                if (!statement.isBlank()) {
                    overlays.add(statement);
                }
            }
        }
        if (extraTexts != null) {
            for (String text : extraTexts) {
                String cleaned = cleanFactStatement(text);
                if (!cleaned.isBlank()) {
                    overlays.add(cleaned);
                }
            }
        }
        return adaptiveHintResolver.resolve(projectId, chapter, overlays);
    }

    private String cleanFactStatement(String statement) {
        if (statement == null) {
            return "";
        }
        return statement.replaceAll("\\s+", " ").trim();
    }

    private String normalizeFactDedupKey(String statement) {
        if (statement == null) {
            return "";
        }
        String text = statement.trim();
        text = text.replaceAll("\\s+", "");
        text = text.replaceAll("[。．.！!?？]+$", "");
        return text;
    }

    private boolean isSimpleRelationFact(String statement) {
        String text = normalizeFactDedupKey(statement);
        if (text.isBlank()) {
            return false;
        }
        return SIMPLE_RELATION_FACT_RE.matcher(text).matches()
                || SIMPLE_RELATION_FACT_RE_EN.matcher(text).matches();
    }

    private double scoreFactStatement(String statement, AdaptiveHintProfile hintProfile) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return 0.0d;
        }

        double score = Math.min(text.length() / 18.0d, 2.0d);
        if (text.contains("，") || text.contains("；") || text.contains("：")
                || text.contains("（") || text.contains("）")
                || text.contains("(") || text.contains(")")) {
            score += 0.7d;
        }
        if (text.matches(".*\\d.*")) {
            score += 0.3d;
        }
        if (containsFactDensityHint(text, hintProfile)) {
            score += 0.8d;
        }
        if (containsFactMechanismHint(text, hintProfile)) {
            score += 0.5d;
        }
        if (containsAny(text, FACT_LOW_VALUE_HINTS)) {
            score -= 0.4d;
        }
        if (containsAny(text, FACT_UNCERTAIN_HINTS)) {
            score -= 0.9d;
        }
        if (isSimpleRelationFact(text)) {
            score -= 0.6d;
        }
        if (looksLikeLowValueSceneFact(text, hintProfile)) {
            score -= 1.0d;
        }
        if (looksLikeDecorativeLabelFact(text)) {
            score -= 0.8d;
        }
        return score;
    }

    private boolean looksLikeLowValueSceneFact(String statement, AdaptiveHintProfile hintProfile) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        if (LOW_VALUE_VISUAL_FACT_RE.matcher(text).matches()) {
            return true;
        }
        return containsAny(text, FACT_LOW_VALUE_HINTS)
                && !containsAny(text, FACT_SCENE_OVERRIDE_HINTS)
                && !containsFactDiscoveryHint(text, hintProfile)
                && !containsAdaptiveHint(text, hintProfile == null ? List.of() : hintProfile.mechanismHints());
    }

    private boolean looksLikeDurableCanonFact(String statement, AdaptiveHintProfile hintProfile) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        if (containsAny(text, FACT_DURABLE_HINTS)
                || containsAdaptiveHint(text, hintProfile == null ? List.of() : hintProfile.durableHints())) {
            return true;
        }
        return containsFactDensityHint(text, hintProfile)
                && (text.contains("可")
                || text.contains("能")
                || text.contains("用于")
                || text.contains("用来")
                || text.contains("意味着")
                || text.contains("本质")
                || text.contains("代价")
                || text.contains("决定")
                || text.contains("命令")
                || text.contains("成为")
                || text.contains("失去")
                || text.contains("获得")
                || text.contains("试图")
                || text.contains("企图"));
    }

    private boolean looksLikeSurvivalCanonFact(String statement, AdaptiveHintProfile hintProfile) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        return (text.contains("受困") || text.contains("隔绝") || text.contains("遗失") || text.contains("见底")
                || text.contains("匮乏") || text.contains("内伤") || text.contains("疗伤")
                || text.contains("人工痕迹") || text.contains("遗迹") || text.contains("入口")
                || text.contains("拖擦声") || text.contains("异响") || text.contains("青光"))
                && !looksLikeLowValueSceneFact(text, hintProfile);
    }

    private boolean looksLikeMomentaryActionFact(String statement) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        return containsAny(text, FACT_MOMENTARY_ACTION_HINTS) && !containsAny(text, FACT_SCENE_OVERRIDE_HINTS);
    }

    private boolean looksLikeTransientWorklogFact(String statement) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        return containsAny(text, FACT_WORKLOG_HINTS) && !containsAny(text, FACT_SCENE_OVERRIDE_HINTS);
    }

    private boolean looksLikeUnnamedGenericActorFact(String statement) {
        String text = cleanFactStatement(statement);
        if (text.isBlank()) {
            return false;
        }
        return FACT_UNNAMED_GENERIC_ACTOR_RE.matcher(text).matches()
                && !containsAny(text, FACT_DURABLE_HINTS)
                && !containsAny(text, FACT_MECHANISM_HINTS);
    }

    private boolean looksLikeDecorativeLabelFact(String statement) {
        String text = cleanFactStatement(statement);
        if (text.isBlank() || !text.contains("：")) {
            return false;
        }
        if (containsAny(text, FACT_DENSITY_HINTS) || containsAny(text, FACT_MECHANISM_HINTS)) {
            return false;
        }
        int idx = text.indexOf('：');
        if (idx <= 0 || idx >= text.length() - 1) {
            return false;
        }
        String left = text.substring(0, idx).trim();
        String right = text.substring(idx + 1).trim();
        return left.length() <= 12
                && right.length() <= 10
                && !right.contains("是")
                && !right.contains("有")
                && !right.contains("可")
                && !right.contains("会")
                && !right.contains("能");
    }

    private boolean containsFactDensityHint(String text, AdaptiveHintProfile hintProfile) {
        return containsAny(text, FACT_DENSITY_HINTS)
                || containsAdaptiveHint(text, hintProfile == null ? List.of() : hintProfile.discoveryHints());
    }

    private boolean containsFactMechanismHint(String text, AdaptiveHintProfile hintProfile) {
        return containsAny(text, FACT_MECHANISM_HINTS)
                || containsAdaptiveHint(text, hintProfile == null ? List.of() : hintProfile.mechanismHints());
    }

    private boolean containsFactDiscoveryHint(String text, AdaptiveHintProfile hintProfile) {
        return containsAny(text, FACT_DISCOVERY_HINTS)
                || containsAdaptiveHint(text, hintProfile == null ? List.of() : hintProfile.discoveryHints());
    }

    private boolean containsAdaptiveHint(String text, List<String> hints) {
        return containsAny(text, hints);
    }

    private List<String> preferredDiscoveryCoverageTokens(AdaptiveHintProfile hintProfile) {
        if (hintProfile != null && hintProfile.queryHints() != null && !hintProfile.queryHints().isEmpty()) {
            List<String> dynamic = hintProfile.queryHints().stream()
                    .filter(token -> token != null && token.length() >= 2)
                    .filter(token -> !FACT_DISCOVERY_HINTS.contains(token))
                    .limit(8)
                    .toList();
            if (!dynamic.isEmpty()) {
                return dynamic;
            }
        }
        return List.of("传承", "入口", "异常", "证据");
    }

    private boolean containsAny(String text, List<String> hints) {
        if (text == null || text.isBlank() || hints == null || hints.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                continue;
            }
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double clampConfidence(Object value) {
        if (value == null) {
            return 0.9d;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return 0.9d;
            }
            return Math.max(0.0d, Math.min(1.0d, parsed));
        } catch (Exception e) {
            return 0.9d;
        }
    }

    private String normalizedFactStatement(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        String statement = node.path("statement").asText("").trim();
        if (!statement.isBlank()) {
            return statement;
        }
        String type = node.path("type").asText("").trim();
        String entity = node.path("entity").asText("").trim();
        String factText = firstNonBlank(
                node.path("fact").asText("").trim(),
                node.path("value").asText("").trim()
        );
        if (!factText.isBlank()) {
            if (entity.isBlank() || factText.contains(entity) || looksLikeCharacterState(type, "")) {
                return factText;
            }
            return entity + "：" + factText;
        }
        String subject = node.path("subject").asText("").trim();
        String predicate = firstNonBlank(
                node.path("predicate").asText("").trim(),
                node.path("attribute").asText("").trim()
        );
        String object = firstNonBlank(
                node.path("object").asText("").trim(),
                node.path("value").asText("").trim()
        );
        if (!subject.isBlank() || !predicate.isBlank() || !object.isBlank()) {
            StringBuilder sb = new StringBuilder();
            if (!subject.isBlank()) sb.append(subject);
            if (!predicate.isBlank()) sb.append(predicate);
            if (!object.isBlank()) sb.append(object);
            return sb.toString().trim();
        }
        return "";
    }

    private boolean looksLikeCharacterState(String type, String predicate) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normalizedPredicate = predicate == null ? "" : predicate.trim();
        return normalizedType.contains("character_state")
                || normalizedPredicate.contains("状态")
                || normalizedPredicate.contains("所处位置")
                || normalizedPredicate.contains("所在地点")
                || normalizedPredicate.contains("位置");
    }

    private boolean looksLikeLocationPredicate(String predicate) {
        String normalizedPredicate = predicate == null ? "" : predicate.trim();
        return normalizedPredicate.contains("位置")
                || normalizedPredicate.contains("地点")
                || normalizedPredicate.contains("所在");
    }

    private String buildCharacterStateText(String predicate, String object) {
        String normalizedPredicate = predicate == null ? "" : predicate.trim();
        String normalizedObject = object == null ? "" : object.trim();
        if (normalizedPredicate.isBlank()) {
            return normalizedObject;
        }
        if (normalizedObject.isBlank()) {
            return normalizedPredicate;
        }
        if (looksLikeLocationPredicate(normalizedPredicate)) {
            return "位于" + normalizedObject;
        }
        return normalizedPredicate + "：" + normalizedObject;
    }

    private List<String> extractJsonStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = extractSummaryItemText(item, List.of("text", "value", "summary", "description"));
                if (!value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
            return values;
        }
        String text = node.asText("").trim();
        if (text.isBlank()) {
            return values;
        }
        for (String part : text.split("[,，/、\\n]")) {
            String value = part.trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, String> extractJsonStringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = extractSummaryItemText(entry.getValue(), List.of("text", "value", "summary", "description"));
                if (!key.isBlank() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
            return values;
        }
        String text = node.asText("").trim();
        if (text.isBlank()) {
            return values;
        }
        for (String part : text.split("[,，;；\\n]")) {
            String[] pair = part.split("[:：]", 2);
            String key = pair.length > 0 ? pair[0].trim() : "";
            String value = pair.length > 1 ? pair[1].trim() : "";
            if (!key.isBlank() && !value.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private boolean isPlausibleCharacterStateSubject(String subject) {
        String normalized = subject == null ? "" : subject.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.length() > 24) {
            return false;
        }
        if (normalized.contains("、") || normalized.contains("，") || normalized.contains(",")) {
            return false;
        }
        if (normalized.contains("的") && looksLikeBodyPartSubject(normalized)) {
            return false;
        }
        return !looksLikeBodyPartSubject(normalized);
    }

    private boolean looksLikeBodyPartSubject(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return false;
        }
        List<String> bodyPartHints = List.of(
                "小腹", "腹部", "胸口", "胸脯", "腰肢", "手指", "指尖", "双腿", "足踝", "脚踝",
                "身体", "肉身", "骨骼", "经脉", "丹田", "识海", "子宫", "神魂", "魔胎"
        );
        for (String hint : bodyPartHints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String buildStructuredCharacterStateSummary(String explicitState,
                                                        String emotionalState,
                                                        List<String> goals,
                                                        List<String> injuries,
                                                        List<String> inventory,
                                                        Map<String, String> relationships,
                                                        String location) {
        String direct = explicitState == null ? "" : explicitState.trim();
        if (!direct.isBlank()) {
            return direct;
        }
        List<String> parts = new ArrayList<>();
        if (emotionalState != null && !emotionalState.isBlank()) {
            parts.add("情绪：" + emotionalState.trim());
        }
        if (goals != null && !goals.isEmpty()) {
            parts.add("目标：" + String.join("、", goals.subList(0, Math.min(goals.size(), 2))));
        }
        List<String> prioritizedInjuries = prioritizeStateHighlights(
                injuries,
                3,
                List.of("闷堵", "冲开", "变化", "内伤", "骨", "寒"),
                List.of()
        );
        if (!prioritizedInjuries.isEmpty()) {
            parts.add("伤势：" + String.join("、", prioritizedInjuries));
        }
        if (relationships != null && !relationships.isEmpty()) {
            Map.Entry<String, String> first = relationships.entrySet().iterator().next();
            parts.add("关系：" + first.getKey() + "→" + first.getValue());
        }
        List<String> prioritizedInventory = prioritizeStateHighlights(
                inventory,
                3,
                List.of("异动", "清水", "残片", "阴", "尖石", "太阴"),
                List.of("遗失", "见底")
        );
        if (!prioritizedInventory.isEmpty()) {
            parts.add("持有：" + String.join("、", prioritizedInventory));
        }
        if (parts.isEmpty() && location != null && !location.isBlank()) {
            parts.add("位于" + location.trim());
        }
        return String.join("；", parts);
    }

    private List<String> prioritizeStateHighlights(List<String> values,
                                                   int limit,
                                                   List<String> highlightTokens,
                                                   List<String> penaltyTokens) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> normalized = normalizeStateList(values);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> sourceOrder = new LinkedHashMap<>();
        for (int i = 0; i < normalized.size(); i++) {
            sourceOrder.putIfAbsent(normalized.get(i), i);
        }
        return normalized.stream()
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(
                            scoreStateHighlightEntry(right, highlightTokens, penaltyTokens),
                            scoreStateHighlightEntry(left, highlightTokens, penaltyTokens)
                    );
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Integer.compare(
                            sourceOrder.getOrDefault(left, Integer.MAX_VALUE),
                            sourceOrder.getOrDefault(right, Integer.MAX_VALUE)
                    );
                })
                .limit(limit)
                .toList();
    }

    private int scoreStateHighlightEntry(String text,
                                         List<String> highlightTokens,
                                         List<String> penaltyTokens) {
        String cleaned = compactInlineText(text);
        if (cleaned.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int score = Math.min(cleaned.length(), 40);
        if (containsAnyToken(cleaned, highlightTokens)) {
            score += 80;
        }
        if (containsAnyToken(cleaned, penaltyTokens)) {
            score -= 20;
        }
        return score;
    }

    private boolean looksLikeTimelineEvent(String type, String predicate, String object) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normalizedPredicate = predicate == null ? "" : predicate.trim();
        String normalizedObject = object == null ? "" : object.trim();
        return normalizedType.contains("event")
                || normalizedType.contains("timeline")
                || normalizedPredicate.contains("发生")
                || normalizedPredicate.contains("完成")
                || normalizedPredicate.contains("进入")
                || normalizedPredicate.contains("成为")
                || normalizedObject.contains("完成")
                || normalizedObject.contains("失败")
                || normalizedObject.contains("死亡")
                || normalizedObject.contains("蜕变");
    }

    private boolean isCanonUpdatesEmpty(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return true;
        }
        return listSize(updates.get("facts")) == 0
                && listSize(updates.get("timeline_events")) == 0
                && listSize(updates.get("character_states")) == 0;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...(truncated)";
    }

    private static Set<Character> toCharSet(String text) {
        LinkedHashSet<Character> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (int i = 0; i < text.length(); i++) {
            result.add(text.charAt(i));
        }
        return result;
    }

    private record CharacterSignal(double positiveScore, double negativeScore, boolean rejected) {
    }

    private record CharacterCandidateHit(String name, String sourceKind) {
    }

    private static final class WorldProposalCandidate {
        private final String name;
        private int mentions;
        private final LinkedHashSet<String> evidenceKinds = new LinkedHashSet<>();

        private WorldProposalCandidate(String name) {
            this.name = name == null ? "" : name.trim();
        }

        private void bump(String evidenceKind) {
            mentions++;
            if (evidenceKind != null && !evidenceKind.isBlank()) {
                evidenceKinds.add(evidenceKind.trim());
            }
        }

        private String name() {
            return name;
        }

        private int mentions() {
            return mentions;
        }

        private String primaryEvidenceKind() {
            if (evidenceKinds.contains("hanlp_world")) {
                return "hanlp_world";
            }
            if (evidenceKinds.contains("world_suffix_rule")) {
                return "world_suffix_rule";
            }
            return evidenceKinds.stream().findFirst().orElse("");
        }

        private List<String> evidenceKinds() {
            List<String> ordered = new ArrayList<>();
            if (evidenceKinds.contains("hanlp_world")) {
                ordered.add("hanlp_world");
            }
            if (evidenceKinds.contains("world_suffix_rule")) {
                ordered.add("world_suffix_rule");
            }
            for (String kind : evidenceKinds) {
                if (!ordered.contains(kind)) {
                    ordered.add(kind);
                }
            }
            return ordered;
        }
    }

    private static final class CharacterProposalCandidate {
        private final String name;
        private final List<String> snippets = new ArrayList<>();
        private final LinkedHashSet<String> sourceKinds = new LinkedHashSet<>();
        private int mentions;
        private double weight;
        private double positiveSignals;
        private double negativeSignals;
        private double structureSignals;
        private int strongStructureMentions;

        private CharacterProposalCandidate(String name) {
            this.name = name == null ? "" : name.trim();
        }

        private void bump(double delta, String snippet, double positiveSignal, double negativeSignal,
                          double structureSignal, boolean strongStructure, String sourceKind) {
            mentions++;
            weight += delta;
            positiveSignals += Math.max(0.0d, positiveSignal);
            negativeSignals += Math.max(0.0d, negativeSignal);
            structureSignals += Math.max(0.0d, structureSignal);
            if (strongStructure) {
                strongStructureMentions++;
            }
            if (sourceKind != null && !sourceKind.isBlank()) {
                sourceKinds.add(sourceKind.trim());
            }
            String normalizedSnippet = snippet == null ? "" : snippet.trim();
            if (!normalizedSnippet.isBlank() && snippets.stream().noneMatch(existing -> normalizeInline(existing).equals(normalizeInline(normalizedSnippet)))) {
                snippets.add(normalizedSnippet);
            }
        }

        private String name() {
            return name;
        }

        private int mentions() {
            return mentions;
        }

        private double weight() {
            return weight;
        }

        private double positiveSignals() {
            return positiveSignals;
        }

        private double negativeSignals() {
            return negativeSignals;
        }

        private double structureSignals() {
            return structureSignals;
        }

        private int strongStructureMentions() {
            return strongStructureMentions;
        }

        private int snippetCount() {
            return snippets.size();
        }

        private double score() {
            return Math.min(mentions, 8) * 1.25d
                    + weight
                    + structureSignals * 0.8d
                    + positiveSignals * 0.9d
                    - negativeSignals * 1.35d;
        }

        private String primarySnippet() {
            if (snippets.isEmpty()) {
                return "";
            }
            String snippet = snippets.get(0);
            return snippet.length() > 60 ? snippet.substring(0, 58) + "…" : snippet;
        }

        private String primaryEvidenceKind() {
            if (sourceKinds.contains("speech")) {
                return "speech_name";
            }
            if (sourceKinds.contains("titled")) {
                return "titled_name";
            }
            if (sourceKinds.contains("leading")) {
                return "leading_name";
            }
            if (sourceKinds.contains("ner")) {
                return "hanlp_person";
            }
            if (sourceKinds.contains("inline")) {
                return "inline_name";
            }
            return sourceKinds.stream().findFirst().orElse("");
        }

        private List<String> evidenceKinds() {
            List<String> ordered = new ArrayList<>();
            addEvidenceKindIfPresent(ordered, "speech", "speech_name");
            addEvidenceKindIfPresent(ordered, "titled", "titled_name");
            addEvidenceKindIfPresent(ordered, "leading", "leading_name");
            addEvidenceKindIfPresent(ordered, "ner", "hanlp_person");
            addEvidenceKindIfPresent(ordered, "inline", "inline_name");
            for (String sourceKind : sourceKinds) {
                String normalized = switch (sourceKind) {
                    case "speech" -> "speech_name";
                    case "titled" -> "titled_name";
                    case "leading" -> "leading_name";
                    case "ner" -> "hanlp_person";
                    case "inline" -> "inline_name";
                    default -> sourceKind;
                };
                if (!ordered.contains(normalized)) {
                    ordered.add(normalized);
                }
            }
            return ordered;
        }

        private void addEvidenceKindIfPresent(List<String> target, String rawSourceKind, String normalizedKind) {
            if (sourceKinds.contains(rawSourceKind) && !target.contains(normalizedKind)) {
                target.add(normalizedKind);
            }
        }

        private String normalizeInline(String text) {
            return text == null ? "" : text.replaceAll("\\s+", "");
        }
    }
}
