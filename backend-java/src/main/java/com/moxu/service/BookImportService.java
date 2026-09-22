package com.moxu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.agent.ArchivistAgent;
import com.moxu.dto.BookImportConfirmRequest;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.StyleCard;
import com.moxu.model.entity.Volume;
import com.moxu.model.entity.WorldCard;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.GraphIndexStorage;
import com.moxu.storage.ProjectStorage;
import com.moxu.storage.VolumeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 书籍导入服务：将 txt/epub 导入为普通项目章节，并执行快速初始化链。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookImportService {

    private static final String CHAPTER_ORDINAL_TOKEN = "[0-9零〇一二两三四五六七八九十百千万ivxIVX]+";
    private static final Pattern VOLUME_HEADING = Pattern.compile(
            "^(?:第?[0-9零〇一二两三四五六七八九十百千万]+[卷册部篇季集]|卷[0-9零〇一二两三四五六七八九十百千万]+|volume\\s*[0-9ivx]+|book\\s+[0-9ivx]+).*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^(?:第?[0-9零〇一二两三四五六七八九十百千万]+[章回节幕集]|(?:序章|楔子|引子|终章|尾声|后记|番外(?:篇)?|卷首语|卷末语))(?:\\s+.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_IMPORT_TITLE_LABEL_PATTERN = Pattern.compile("^(?i:title|标题|章节标题)\\s*[:：-]\\s*");
    private static final Pattern LEADING_IMPORT_EN_CHAPTER_PATTERN = Pattern.compile("^(?i:chapter\\s*[0-9ivx]+\\s*[:：\\-—]?\\s*)");
    private static final Pattern LEADING_IMPORT_CN_CHAPTER_PATTERN = Pattern.compile(
            "^(?:第\\s*" + CHAPTER_ORDINAL_TOKEN + "\\s*[章节回节幕集]\\s*[:：\\-—]?\\s*)");
    private static final Pattern LEADING_CHAPTER_MARKER_PATTERN = Pattern.compile(
            "^(?:(?:第\\s*" + CHAPTER_ORDINAL_TOKEN + "\\s*[章节回节幕集])|(?:序章|楔子|引子|终章|尾声|后记|番外(?:篇)?|卷首语|卷末语))\\s*[:：\\-—]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORLD_TOKEN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,12}(?:宗|门|派|阁|宫|殿|城|国|界|山|谷|岛|洲|域|族|诀|功|术|法|塔|府|院|堂|会|盟|监狱|学院|组织|公司|集团))");
    private static final Pattern SPEECH_NAME = Pattern.compile("([\\p{IsHan}]{2,3})(?:轻声|低声|冷声|寒声|哑声|沉声|缓缓)?(?=道|说道|问道|开口|说|问|笑|哭|想)");
    private static final Pattern LEADING_HAN_BLOCK = Pattern.compile("^[\\p{IsHan}]{2,8}");
    private static final int TXT_SPLIT_TARGET = 4500;
    private static final int TXT_SPLIT_MAX = 7000;
    private static final int BLANK_GAP_CHAPTER_MIN = 600;
    private static final int TITLE_EXTRACTION_BATCH_SIZE = 8;
    private static final int TITLE_EXTRACTION_SNIPPET_LIMIT = 900;
    private static final int STYLE_EXCERPT_LIMIT = 6;
    private static final int STYLE_EXCERPT_MIN = 35;
    private static final int STYLE_EXCERPT_MAX = 260;
    private static final int CARD_SUMMARY_CONTEXT_LIMIT = 14000;
    private static final int STYLE_CONTEXT_LIMIT = 5000;
    private static final Set<String> NOISE_DOC_TITLES = Set.of("目录", "cover", "封面", "版权", "扉页", "title", "toc", "navigation");
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
    private static final Pattern HUMAN_CARD_NAME_SPLITTER = Pattern.compile("[/／|｜]+");
    private static final List<String> IMPORT_CHARACTER_DESCRIPTION_HINTS = List.of(
            "其女儿", "其儿子", "之女", "之子", "女儿", "儿子", "父亲", "母亲", "丈夫", "妻子",
            "情感纠葛", "感情纠葛", "暧昧", "一级警员", "二级警司", "警员", "警司", "局长", "小姐", "先生"
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
    private static final Pattern TEMPORAL_SEQUENCE = Pattern.compile(
            "^第?[0-9零〇一二两三四五六七八九十百千万]+(?:天|日|周|月|年|次)$"
    );
    private static final Set<String> TEMPORAL_NARRATIVE_TOKENS = Set.of(
            "今天", "昨日", "昨天", "明天", "后天", "当天", "那天", "这天", "当日", "次日", "翌日", "隔日",
            "今夜", "昨夜", "当夜", "夜里", "深夜", "半夜", "凌晨", "清晨", "早晨", "清早", "早上",
            "上午", "中午", "午后", "下午", "傍晚", "晚上", "白天", "白日", "白昼", "夜晚"
    );
    private static final Set<Character> CHARACTER_DISCOURSE_ENDING_CHARS = Set.of(
            '的', '地', '得', '了', '着', '过', '在', '把', '将', '被', '向', '从', '对', '与', '和', '跟',
            '并', '且', '但', '却', '而', '又', '还', '都', '也', '就', '便', '再', '正', '于', '是', '有',
            '没', '未'
    );
    private static final Set<Character> CHARACTER_LOCATION_ENDING_CHARS = Set.of(
            '里', '中', '内', '外', '边', '上', '下', '前', '后'
    );
    private static final Set<String> CHARACTER_POSITIVE_RIGHT_HINTS = Set.of(
            "说", "问", "答", "笑", "哭", "想", "看", "望", "盯", "道", "叫", "喊", "骂",
            "开口", "低声", "轻声", "沉声", "抬头", "低头", "点头", "摇头", "伸手", "抬手",
            "走", "跑", "冲", "扑", "抱", "亲", "吻", "坐", "站", "看着", "望着"
    );
    private static final Set<String> CHARACTER_NEGATIVE_RIGHT_HINTS = Set.of(
            "有", "还有", "没有", "收拾", "收拾好了", "那边", "这边", "里面", "外面", "附近",
            "不大不小", "不小", "很大", "太大", "太小"
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
    private static final String CHARACTER_INLINE_LEFT_BOUNDARIES = " \t\r\n\"'“”‘’《》「」『』（）()【】、，。！？；：和与跟对向给把将被由替朝往从是为";
    private static final String CHARACTER_INLINE_RIGHT_BOUNDARIES = " \t\r\n\"'“”‘’《》「」『』（）()【】、，。！？；：的和与跟在向给把将被都也仍并则让令叫说问笑哭想看听道呢吗啊呀曾会能可便就又还已正来去过着了";
    private static final Set<String> WORLD_SUFFIX_STOP = Set.of("身体", "神魂", "丹田", "子宫", "肉身", "魔胎", "小腹");
    private static final Set<String> WORLD_ABSTRACT_STOPWORDS = Set.of(
            "天地", "力量", "气息", "局势", "虚空", "真相", "天赋", "修为", "实力", "命运", "故事", "章节", "世界"
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
            "自己", "这里", "那里", "这个", "那个", "这些", "那些", "他们", "她们", "我们", "你们"
    );
    private static final Set<Character> WORLD_FUNCTION_CHARS = Set.of(
            '的', '地', '得', '了', '着', '过', '并', '将', '把', '被', '给', '向', '从', '对', '在', '和', '与', '跟', '而', '却', '还', '又'
    );
    private static final Set<Character> WORLD_PRONOUN_CHARS = Set.of('我', '你', '他', '她', '它');
    private static final Set<Character> WORLD_CLASSIFIER_LEADING_CHARS = Set.of('个', '座', '片', '处', '间', '条');
    private static final Set<String> STYLE_META_PHRASES = Set.of(
            "本章", "这一章", "这一回", "章节", "摘要", "总结", "概括", "设定", "风格", "导入", "说明", "例如", "比如", "建议", "提示"
    );
    private static final Set<String> STYLE_NARRATIVE_HINTS = Set.of(
            "看", "望", "听", "笑", "哭", "踏", "走", "来", "去", "抬", "按", "握", "坐", "立", "转", "落", "扑", "压", "推", "掀",
            "风", "雨", "夜", "光", "香", "血", "衣", "眸", "声", "脚", "手", "心", "痛", "冷", "热", "门", "殿", "阁", "廊"
    );

    private final DraftStorage draftStorage;
    private final VolumeStorage volumeStorage;
    private final CardStorage cardStorage;
    private final GraphIndexStorage graphIndexStorage;
    private final ProjectStorage projectStorage;
    private final ArchivistAgent archivistAgent;
    private final LlmGateway llmGateway;
    private final ImportRelationshipBootstrapper importRelationshipBootstrapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> preview(String filename, String contentType, byte[] bytes) throws IOException {
        ParsedBook book = parseBook(filename, contentType, bytes);
        return buildPreviewResult(book, filename);
    }

    public Map<String, Object> previewText(String sourceName, String text) throws IOException {
        String resolvedSourceName = firstNonBlank(sourceName, "粘贴导入.txt");
        ParsedBook book = parseTextBook(resolvedSourceName, text);
        return buildPreviewResult(book, resolvedSourceName);
    }

    private Map<String, Object> buildPreviewResult(ParsedBook book, String filename) {
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (int i = 0; i < book.volumes().size(); i++) {
            ParsedVolume volume = book.volumes().get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i + 1);
            item.put("title", volume.title());
            item.put("explicit", volume.explicit());
            item.put("chapter_count", volume.chapters().size());
            item.put("sample_titles", volume.chapters().stream().limit(8).map(ParsedChapter::title).toList());
            List<Map<String, Object>> chapters = new ArrayList<>();
            for (int chapterIndex = 0; chapterIndex < volume.chapters().size(); chapterIndex++) {
                ParsedChapter chapter = volume.chapters().get(chapterIndex);
                String content = chapter.content() != null ? chapter.content() : "";
                Map<String, Object> chapterItem = new LinkedHashMap<>();
                chapterItem.put("index", chapterIndex + 1);
                chapterItem.put("title", chapter.title());
                chapterItem.put("synthetic_title", chapter.syntheticTitle());
                chapterItem.put("selected", true);
                chapterItem.put("word_count", content.length());
                chapterItem.put("content", content);
                chapterItem.put("content_preview", buildTitleExtractionSnippet(content));
                chapters.add(chapterItem);
            }
            item.put("chapters", chapters);
            volumes.add(item);
        }

        return Map.of(
                "success", true,
                "filename", filename != null ? filename : "",
                "format", book.format(),
                "volume_count", book.volumes().size(),
                "chapter_count", book.chapterCount(),
                "volumes", volumes,
                "warnings", book.warnings()
        );
    }

    public Map<String, Object> importPreparedBook(String projectId,
                                                  BookImportConfirmRequest request) throws IOException {
        if (request == null) {
            throw new IOException("导入请求不能为空");
        }
        String sourceName = firstNonBlank(request.getSourceName(), "导入书籍.txt");
        ParsedBook preparedBook = requestToParsedBook(sourceName, request);
        return importParsedBook(
                projectId,
                sourceName,
                preparedBook,
                request.getQuickInit() == null || request.getQuickInit(),
                request.getOverwriteStyle() != null && request.getOverwriteStyle(),
                request.getOverwriteCards() != null && request.getOverwriteCards(),
                request.getAutoExtractTitles() != null && request.getAutoExtractTitles(),
                request.getMaxCharacterCards(),
                request.getMaxWorldCards()
        );
    }

    private ParsedBook requestToParsedBook(String sourceName, BookImportConfirmRequest request) {
        List<ParsedVolume> volumes = new ArrayList<>();
        if (request.getVolumes() != null) {
            for (BookImportConfirmRequest.VolumeDraft volumeDraft : request.getVolumes()) {
                if (volumeDraft == null) {
                    continue;
                }
                List<ParsedChapter> chapters = new ArrayList<>();
                if (volumeDraft.getChapters() != null) {
                    for (BookImportConfirmRequest.ChapterDraft chapterDraft : volumeDraft.getChapters()) {
                        if (chapterDraft == null) {
                            continue;
                        }
                        if (chapterDraft.getSelected() != null && !chapterDraft.getSelected()) {
                            continue;
                        }
                        chapters.add(new ParsedChapter(
                                firstNonBlank(chapterDraft.getTitle(), ""),
                                chapterDraft.getContent() != null ? chapterDraft.getContent() : "",
                                chapterDraft.getSyntheticTitle() != null && chapterDraft.getSyntheticTitle()
                        ));
                    }
                }
                if (!chapters.isEmpty()) {
                    volumes.add(new ParsedVolume(
                            firstNonBlank(volumeDraft.getTitle(), defaultVolumeLabel(sourceName)),
                            volumeDraft.getExplicit() != null && volumeDraft.getExplicit(),
                            chapters
                    ));
                }
            }
        }
        return new ParsedBook(
                firstNonBlank(request.getFormat(), "txt"),
                sourceName,
                volumes.stream().anyMatch(ParsedVolume::explicit),
                volumes,
                List.of()
        );
    }

    private Map<String, Object> importParsedBook(String projectId,
                                                 String filename,
                                                 ParsedBook book,
                                                 boolean quickInit,
                                                 boolean overwriteStyle,
                                                 boolean overwriteCards,
                                                 boolean autoExtractTitles,
                                                 Integer maxCharacterCards,
                                                 Integer maxWorldCards) throws IOException {
        if (book.chapterCount() == 0) {
            throw new IOException("未能从文件中识别出可导入的章节内容");
        }

        int existingMaxVolume = determineExistingMaxVolume(projectId);
        boolean hasExistingStructure = existingMaxVolume > 0;
        boolean explicitVolumes = book.explicitVolumes();
        String projectDisplayName = resolveProjectDisplayName(projectId, filename);
        TitleExtractionPreparation titlePreparation = maybeAutoExtractTitles(book, projectDisplayName, autoExtractTitles);
        ParsedBook preparedBook = titlePreparation.book();

        List<ImportedChapterRef> importedChapters = new ArrayList<>();
        List<Map<String, Object>> importedVolumes = new ArrayList<>();
        String implicitVolumeTargetId = null;

        for (int volumeIdx = 0; volumeIdx < preparedBook.volumes().size(); volumeIdx++) {
            ParsedVolume parsedVolume = preparedBook.volumes().get(volumeIdx);
            String targetVolumeId;
            if (explicitVolumes) {
                int targetNumber = hasExistingStructure ? existingMaxVolume + volumeIdx + 1 : volumeIdx + 1;
                targetVolumeId = "V" + targetNumber;
            } else {
                if (implicitVolumeTargetId == null) {
                    int targetNumber = hasExistingStructure ? existingMaxVolume + 1 : 1;
                    implicitVolumeTargetId = "V" + targetNumber;
                }
                targetVolumeId = implicitVolumeTargetId;
            }

            String volumeTitle = resolveImportedVolumeTitle(filename, parsedVolume, targetVolumeId, explicitVolumes, projectDisplayName);
            Volume volume = volumeStorage.upsertVolume(projectId, targetVolumeId, volumeTitle, "", parseVolumeNumber(targetVolumeId));

            List<Map<String, Object>> chapterMaps = new ArrayList<>();
            for (int chapterIdx = 0; chapterIdx < parsedVolume.chapters().size(); chapterIdx++) {
                ParsedChapter parsedChapter = parsedVolume.chapters().get(chapterIdx);
                String chapterId = targetVolumeId + "C" + (chapterIdx + 1);
                String chapterTitle = normalizeChapterTitle(parsedChapter.title(), chapterIdx + 1, filename, projectDisplayName);
                String content = normalizeImportedContent(parsedChapter.content());

                draftStorage.saveFinalDraft(projectId, chapterId, content);
                ChapterSummary placeholder = ChapterSummary.builder()
                        .chapter(chapterId)
                        .volumeId(targetVolumeId)
                        .orderIndex(chapterIdx)
                        .title(chapterTitle)
                        .wordCount(content.length())
                        .briefSummary("")
                        .keyEvents(new ArrayList<>())
                        .newFacts(new ArrayList<>())
                        .characterStateChanges(new ArrayList<>())
                        .openLoops(new ArrayList<>())
                        .build();
                draftStorage.saveChapterSummary(projectId, placeholder);

                importedChapters.add(new ImportedChapterRef(chapterId, targetVolumeId, chapterTitle, content, chapterIdx));
                chapterMaps.add(Map.of(
                        "chapter", chapterId,
                        "title", chapterTitle,
                        "word_count", content.length()
                ));
            }

            importedVolumes.add(Map.of(
                    "volume_id", volume.getId(),
                    "title", volume.getTitle(),
                    "chapter_count", parsedVolume.chapters().size(),
                    "chapters", chapterMaps
            ));
        }

        Map<String, Object> quickInitResult = new LinkedHashMap<>();
        if (quickInit) {
            quickInitResult = runQuickInitialization(
                    projectId,
                    filename,
                    importedChapters,
                    overwriteStyle,
                    overwriteCards,
                    maxCharacterCards != null && maxCharacterCards > 0 ? maxCharacterCards : 9,
                    maxWorldCards != null && maxWorldCards > 0 ? maxWorldCards : computeWorldCardBudget(importedChapters.size())
            );
        } else {
            quickInitResult.put("enabled", false);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("filename", filename != null ? filename : "");
        response.put("format", preparedBook.format());
        response.put("imported_volume_count", importedVolumes.size());
        response.put("imported_chapter_count", importedChapters.size());
        response.put("volumes", importedVolumes);
        response.put("warnings", preparedBook.warnings());
        response.put("title_extraction", Map.of(
                "enabled", titlePreparation.enabled(),
                "candidate_count", titlePreparation.candidateCount(),
                "replaced", titlePreparation.replacedCount()
        ));
        response.put("quick_init", quickInitResult);
        return response;
    }

    private TitleExtractionPreparation maybeAutoExtractTitles(ParsedBook book,
                                                              String projectDisplayName,
                                                              boolean autoExtractTitles) {
        if (book == null) {
            return new TitleExtractionPreparation(null, autoExtractTitles, 0, 0);
        }
        List<SyntheticTitleCandidate> candidates = collectSyntheticTitleCandidates(book);
        if (!autoExtractTitles || candidates.isEmpty() || llmGateway == null) {
            return new TitleExtractionPreparation(book, autoExtractTitles, candidates.size(), 0);
        }

        Map<String, String> replacements = new HashMap<>();
        for (int start = 0; start < candidates.size(); start += TITLE_EXTRACTION_BATCH_SIZE) {
            int end = Math.min(candidates.size(), start + TITLE_EXTRACTION_BATCH_SIZE);
            List<SyntheticTitleCandidate> batch = candidates.subList(start, end);
            try {
                Map<Integer, String> extracted = extractTitlesForBatch(book.sourceName(), projectDisplayName, batch);
                for (Map.Entry<Integer, String> entry : extracted.entrySet()) {
                    int batchIndex = entry.getKey() - 1;
                    if (batchIndex < 0 || batchIndex >= batch.size()) {
                        continue;
                    }
                    SyntheticTitleCandidate candidate = batch.get(batchIndex);
                    String normalized = normalizeExtractedChapterTitle(
                            entry.getValue(),
                            candidate,
                            projectDisplayName,
                            book.sourceName()
                    );
                    if (!normalized.isBlank()) {
                        replacements.put(candidate.key(), normalized);
                    }
                }
            } catch (Exception e) {
                log.warn("导入标题提取失败，保留兜底标题: {}", e.getMessage());
            }
        }

        if (replacements.isEmpty()) {
            return new TitleExtractionPreparation(book, true, candidates.size(), 0);
        }

        int replacedCount = 0;
        List<ParsedVolume> updatedVolumes = new ArrayList<>();
        for (int volumeIndex = 0; volumeIndex < book.volumes().size(); volumeIndex++) {
            ParsedVolume volume = book.volumes().get(volumeIndex);
            List<ParsedChapter> updatedChapters = new ArrayList<>();
            for (int chapterIndex = 0; chapterIndex < volume.chapters().size(); chapterIndex++) {
                ParsedChapter chapter = volume.chapters().get(chapterIndex);
                String replacement = replacements.get(buildSyntheticCandidateKey(volumeIndex, chapterIndex));
                if (replacement != null && !replacement.isBlank() && !replacement.equals(cleanHeading(chapter.title()))) {
                    updatedChapters.add(new ParsedChapter(replacement, chapter.content(), false));
                    replacedCount++;
                } else {
                    updatedChapters.add(chapter);
                }
            }
            updatedVolumes.add(new ParsedVolume(volume.title(), volume.explicit(), updatedChapters));
        }

        return new TitleExtractionPreparation(
                new ParsedBook(book.format(), book.sourceName(), book.explicitVolumes(), updatedVolumes, book.warnings()),
                true,
                candidates.size(),
                replacedCount
        );
    }

    ParsedBook parseTextBook(String sourceName, String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        List<VolumeSeed> seeds = new ArrayList<>();
        VolumeSeed currentVolume = new VolumeSeed("", false);
        seeds.add(currentVolume);
        String currentChapterTitle = null;
        StringBuilder currentBuffer = new StringBuilder();

        for (String rawLine : normalized.split("\n", -1)) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isBlank()) {
                if (!currentBuffer.isEmpty()) {
                    currentBuffer.append("\n\n");
                }
                continue;
            }
            if (looksLikeVolumeHeading(line)) {
                flushChapter(currentVolume, currentChapterTitle, currentBuffer);
                currentChapterTitle = null;
                currentVolume = new VolumeSeed(cleanHeading(line), true);
                seeds.add(currentVolume);
                continue;
            }
            if (looksLikeChapterHeading(line)) {
                flushChapter(currentVolume, currentChapterTitle, currentBuffer);
                currentChapterTitle = cleanHeading(line);
                continue;
            }
            if (!currentBuffer.isEmpty() && !currentBuffer.toString().endsWith("\n\n")) {
                currentBuffer.append('\n');
            }
            currentBuffer.append(rawLine.stripTrailing());
        }
        flushChapter(currentVolume, currentChapterTitle, currentBuffer);

        List<ParsedVolume> volumes = seeds.stream()
                .filter(seed -> !seed.chapters.isEmpty())
                .map(seed -> new ParsedVolume(
                        seed.title == null || seed.title.isBlank() ? defaultVolumeLabel(sourceName) : seed.title,
                        seed.explicit,
                        seed.chapters.stream()
                                .map(ch -> new ParsedChapter(ch.title, ch.content, ch.syntheticTitle))
                                .toList()
                ))
                .toList();

        if (volumes.isEmpty() || volumes.stream().allMatch(volume -> volume.chapters().size() <= 1 && !volume.explicit())) {
            List<ParsedChapter> fallbackChapters = splitByLargeBlankGaps(normalized, baseName(sourceName));
            if (fallbackChapters.size() < 2) {
                fallbackChapters = splitIntoSyntheticChapters(normalized, baseName(sourceName));
            }
            volumes = List.of(new ParsedVolume(defaultVolumeLabel(sourceName), false, fallbackChapters));
        }

        boolean explicitVolumes = volumes.stream().anyMatch(ParsedVolume::explicit);
        return new ParsedBook("txt", sourceName, explicitVolumes, volumes, List.of());
    }

    private List<SyntheticTitleCandidate> collectSyntheticTitleCandidates(ParsedBook book) {
        if (book == null || book.volumes() == null || book.volumes().isEmpty()) {
            return List.of();
        }
        List<SyntheticTitleCandidate> candidates = new ArrayList<>();
        for (int volumeIndex = 0; volumeIndex < book.volumes().size(); volumeIndex++) {
            ParsedVolume volume = book.volumes().get(volumeIndex);
            if (volume == null || volume.chapters() == null || volume.chapters().isEmpty()) {
                continue;
            }
            for (int chapterIndex = 0; chapterIndex < volume.chapters().size(); chapterIndex++) {
                ParsedChapter chapter = volume.chapters().get(chapterIndex);
                if (chapter == null || !chapter.syntheticTitle()) {
                    continue;
                }
                candidates.add(new SyntheticTitleCandidate(volumeIndex, chapterIndex, chapter));
            }
        }
        return candidates;
    }

    private Map<Integer, String> extractTitlesForBatch(String sourceName,
                                                       String projectDisplayName,
                                                       List<SyntheticTitleCandidate> batch) {
        if (batch == null || batch.isEmpty()) {
            return Map.of();
        }
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            SyntheticTitleCandidate candidate = batch.get(i);
            String snippet = buildTitleExtractionSnippet(candidate.chapter().content());
            material.append("章节 ").append(i + 1).append('\n')
                    .append("当前标题: ").append(firstNonBlank(candidate.chapter().title(), projectDisplayName + "-" + (candidate.chapterIndex() + 1))).append('\n')
                    .append("正文片段:\n")
                    .append(snippet)
                    .append("\n\n");
        }

        String systemPrompt = "你是小说章节命名助手，擅长从正文片段中提炼简洁、自然、有画面感的章节标题。";
        String userPrompt = "请为以下小说章节片段生成更自然的标题，只处理那些原本没有识别出章节标题、现在使用兜底标题的章节。"
                + "要求：\n"
                + "- 保持原文语言\n"
                + "- 中文标题建议 6-18 字，尽量像网文章节名而不是摘要句\n"
                + "- 不要补“第x章”或“Chapter x”\n"
                + "- 不要加引号、书名号或解释\n"
                + "- 信息不足时，可以保留较中性的短标题，但不要输出当前兜底标题\n"
                + "- 严格输出 JSON，不要任何额外文本\n"
                + "格式：{\"items\":[{\"index\":1,\"title\":\"标题\"}]}\n\n"
                + "来源：" + firstNonBlank(projectDisplayName, baseName(sourceName)) + "\n\n"
                + material;
        LlmResponse response = llmGateway.chat(
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                llmGateway.getProviderForAgent("extractor"),
                0.3d,
                900
        );
        return parseTitleExtractionResponse(response.getContent(), batch.size());
    }

    private Map<Integer, String> parseTitleExtractionResponse(String content, int batchSize) {
        if (batchSize <= 0) {
            return Map.of();
        }
        String json = extractJson(content);
        if ((json == null || json.isBlank()) && content != null) {
            String trimmed = content.trim();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
                trimmed = trimmed.replaceFirst("\\s*```$", "");
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                json = trimmed;
            }
        }
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                items = root.path("titles");
            }
            if (!items.isArray() && root.isArray()) {
                items = root;
            }
            if (!items.isArray()) {
                return Map.of();
            }
            Map<Integer, String> result = new LinkedHashMap<>();
            for (JsonNode item : items) {
                if (item == null || item.isNull()) {
                    continue;
                }
                int index = item.path("index").asInt(-1);
                if (index < 1 || index > batchSize) {
                    continue;
                }
                String title = textValue(item, "title");
                if (!title.isBlank()) {
                    result.put(index, title);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析导入标题提取响应失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildTitleExtractionSnippet(String content) {
        String normalized = normalizeImportedContent(content);
        if (normalized.length() <= TITLE_EXTRACTION_SNIPPET_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, TITLE_EXTRACTION_SNIPPET_LIMIT) + "...";
    }

    private String normalizeExtractedChapterTitle(String title,
                                                  SyntheticTitleCandidate candidate,
                                                  String projectDisplayName,
                                                  String sourceName) {
        String normalized = cleanHeading(title);
        normalized = normalized.replaceAll("^[\"'“”‘’《》【】\\[\\](){}<>]+", "");
        normalized = normalized.replaceAll("[\"'“”‘’《》【】\\[\\](){}<>]+$", "");
        normalized = stripLeadingImportedTitleDecorators(normalized);
        normalized = cleanHeading(normalized);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() > 32) {
            return "";
        }
        String currentTitle = cleanHeading(candidate.chapter().title());
        int normalizedIndex = candidate.chapterIndex() + 1;
        if (normalized.equals(currentTitle)) {
            return "";
        }
        if (isSyntheticFallbackTitle(normalized, sourceName, normalizedIndex)
                || isSyntheticFallbackTitle(normalized, projectDisplayName, normalizedIndex)) {
            return "";
        }
        return normalized;
    }

    private String stripLeadingImportedTitleDecorators(String text) {
        String cleaned = text == null ? "" : text.trim();
        for (int i = 0; i < 6; i++) {
            String before = cleaned;
            cleaned = LEADING_IMPORT_TITLE_LABEL_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = LEADING_IMPORT_EN_CHAPTER_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = LEADING_IMPORT_CN_CHAPTER_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = cleaned.trim();
            if (cleaned.equals(before.trim())) {
                break;
            }
        }
        return cleaned;
    }

    private String buildSyntheticCandidateKey(int volumeIndex, int chapterIndex) {
        return volumeIndex + ":" + chapterIndex;
    }

    int computeWorldCardBudget(int chapterCount) {
        if (chapterCount <= 0) {
            return 3;
        }
        int budget = (int) Math.ceil(Math.log(chapterCount + 1) * 2.0d);
        return Math.max(3, Math.min(10, budget));
    }

    private Map<String, Object> runQuickInitialization(String projectId,
                                                       String filename,
                                                       List<ImportedChapterRef> importedChapters,
                                                       boolean overwriteStyle,
                                                       boolean overwriteCards,
                                                       int maxCharacterCards,
                                                       int maxWorldCards) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);

        int summariesGenerated = 0;
        List<ChapterSummary> savedSummaries = new ArrayList<>();
        for (ImportedChapterRef ref : importedChapters) {
            try {
                ChapterSummary summary = archivistAgent.generateChapterSummary(projectId, ref.chapterId(), ref.title(), ref.content());
                summary.setChapter(ref.chapterId());
                summary.setVolumeId(ref.volumeId());
                summary.setOrderIndex(ref.orderIndex());
                summary.setTitle(firstNonBlank(summary.getTitle(), ref.title()));
                summary.setWordCount(ref.content().length());
                draftStorage.saveChapterSummary(projectId, summary);
                savedSummaries.add(summary);
                summariesGenerated++;
            } catch (Exception e) {
                log.warn("导入后生成章节摘要失败: {}/{} - {}", projectId, ref.chapterId(), e.getMessage());
                draftStorage.getChapterSummary(projectId, ref.chapterId()).ifPresent(savedSummaries::add);
            }
        }
        result.put("summaries_generated", summariesGenerated);

        Map<String, Object> textChunkMeta = draftStorage.rebuildTextChunkIndex(projectId);
        result.put("text_chunks", Map.of(
                "rebuilt", true,
                "meta", textChunkMeta
        ));

        Map<String, Object> cardsResult = initializeCards(projectId, savedSummaries, overwriteCards, maxCharacterCards, maxWorldCards);
        result.put("cards", cardsResult);

        Map<String, Object> relationshipResult = initializeRelationshipIndexes(projectId, importedChapters, savedSummaries);
        result.put("relationship_indexes", relationshipResult);

        Map<String, Object> styleResult = initializeStylePackage(projectId, filename, importedChapters, overwriteStyle);
        result.put("style_package", styleResult);
        return result;
    }

    private Map<String, Object> initializeCards(String projectId,
                                                List<ChapterSummary> summaries,
                                                boolean overwriteCards,
                                                int maxCharacterCards,
                                                int maxWorldCards) {
        if (summaries == null || summaries.isEmpty()) {
            return Map.of(
                    "characters_created", 0,
                    "world_created", 0,
                    "skipped", true,
                    "reason", "no summaries"
            );
        }

        Map<String, Object> extracted = extractCardsFromSummaries(summaries, maxCharacterCards, maxWorldCards);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characterMaps = (List<Map<String, Object>>) extracted.getOrDefault("characters", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worldMaps = (List<Map<String, Object>>) extracted.getOrDefault("world", List.of());

        int characterCreated = 0;
        int characterSkipped = 0;
        for (Map<String, Object> item : characterMaps) {
            String name = stringValue(item, "name").trim();
            if (name.isBlank()) {
                continue;
            }
            if (!overwriteCards && cardStorage.getCharacterCard(projectId, name).isPresent()) {
                characterSkipped++;
                continue;
            }
            CharacterCard card = CharacterCard.builder()
                    .name(name)
                    .description(firstNonBlank(stringValue(item, "description"), "导入文本中的核心角色，建议后续结合章节细化。"))
                    .aliases(stringList(item.get("aliases")))
                    .stars(intValue(item.get("stars"), 1))
                    .build();
            try {
                cardStorage.saveCharacterCard(projectId, card);
                characterCreated++;
            } catch (IOException e) {
                log.warn("保存导入角色卡失败: {} - {}", name, e.getMessage());
            }
        }

        int worldCreated = 0;
        int worldSkipped = 0;
        for (Map<String, Object> item : worldMaps) {
            String name = stringValue(item, "name").trim();
            if (name.isBlank()) {
                continue;
            }
            if (!overwriteCards && cardStorage.getWorldCard(projectId, name).isPresent()) {
                worldSkipped++;
                continue;
            }
            WorldCard card = WorldCard.builder()
                    .name(name)
                    .description(firstNonBlank(stringValue(item, "description"), "导入文本中多次出现的重要设定。"))
                    .aliases(stringList(item.get("aliases")))
                    .category(firstNonBlank(stringValue(item, "category"), "concept"))
                    .rules(stringList(item.get("rules")))
                    .stars(intValue(item.get("stars"), 1))
                    .build();
            try {
                cardStorage.saveWorldCard(projectId, card);
                worldCreated++;
            } catch (IOException e) {
                log.warn("保存导入世界卡失败: {} - {}", name, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characters_created", characterCreated);
        result.put("characters_skipped_existing", characterSkipped);
        result.put("world_created", worldCreated);
        result.put("world_skipped_existing", worldSkipped);
        result.put("max_character_cards", maxCharacterCards);
        result.put("max_world_cards", maxWorldCards);
        return result;
    }

    private Map<String, Object> initializeStylePackage(String projectId,
                                                       String filename,
                                                       List<ImportedChapterRef> importedChapters,
                                                       boolean overwriteStyle) {
        Optional<StyleCard> existing = cardStorage.getStyleCard(projectId);
        if (!overwriteStyle && existing.filter(card -> card.getStyle() != null && !card.getStyle().isBlank()).isPresent()) {
            return Map.of(
                    "saved", false,
                    "skipped_existing", true,
                    "excerpt_count", existing.map(card -> card.getRepresentativeExcerpts() != null ? card.getRepresentativeExcerpts().size() : 0).orElse(0)
            );
        }

        List<ExcerptCandidate> excerpts = selectRepresentativeExcerpts(importedChapters, STYLE_EXCERPT_LIMIT);
        String style = extractStyleDescription(filename, excerpts);
        StyleCard card = StyleCard.builder()
                .style(style)
                .representativeExcerpts(excerpts.stream().map(ExcerptCandidate::text).toList())
                .sourceChapters(excerpts.stream().map(ExcerptCandidate::chapterId).distinct().toList())
                .build();
        try {
            cardStorage.saveStyleCard(projectId, card);
        } catch (IOException e) {
            log.warn("保存导入 style package 失败: {}", e.getMessage());
            return Map.of(
                    "saved", false,
                    "error", e.getMessage(),
                    "excerpt_count", excerpts.size()
            );
        }
        return Map.of(
                "saved", true,
                "excerpt_count", excerpts.size(),
                "source_chapters", card.getSourceChapters() != null ? card.getSourceChapters() : List.of()
        );
    }

    private String extractStyleDescription(String filename, List<ExcerptCandidate> excerpts) {
        if (excerpts == null || excerpts.isEmpty()) {
            return "导入文本的风格片段不足，建议后续补充章节摘要与卡片后再细化文风。";
        }
        StringBuilder material = new StringBuilder();
        for (ExcerptCandidate excerpt : excerpts) {
            material.append("[").append(excerpt.chapterId()).append("] ")
                    .append(excerpt.text())
                    .append("\n\n");
        }
        String clipped = material.length() > STYLE_CONTEXT_LIMIT
                ? material.substring(0, STYLE_CONTEXT_LIMIT) + "..."
                : material.toString();
        String systemPrompt = "你是一位专业的小说文风分析师，擅长从少量代表性片段提炼稳定、可模仿的写作风格。";
        String userPrompt = "请基于以下代表性片段，总结这本书最值得模仿的文风特征。"
                + "要求：150-220字，覆盖叙事视角、句式节奏、用词质感、情绪推进、段落习惯。"
                + "直接输出文风描述，不要加标题。\n\n书名/来源：" + baseName(filename) + "\n\n片段：\n" + clipped;
        try {
            LlmResponse response = llmGateway.chat(
                    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    llmGateway.getProviderForAgent("extractor"),
                    null,
                    900
            );
            String text = response.getContent() != null ? response.getContent().trim() : "";
            if (!text.isBlank()) {
                return text;
            }
        } catch (Exception e) {
            log.warn("导入 style package LLM 提取失败: {}", e.getMessage());
        }
        return "整体偏中文网文叙事风格，可重点参考原文在段落推进、情绪落点和叙述节奏上的处理。";
    }

    private Map<String, Object> initializeRelationshipIndexes(String projectId,
                                                              List<ImportedChapterRef> importedChapters,
                                                              List<ChapterSummary> summaries) {
        List<CharacterCard> characterCards = cardStorage.listCharacterCards(projectId).stream()
                .map(name -> cardStorage.getCharacterCard(projectId, name).orElse(null))
                .filter(card -> card != null && card.getName() != null && !card.getName().isBlank())
                .toList();
        if (characterCards.isEmpty()) {
            return Map.of(
                    "built", false,
                    "reason", "no_character_cards"
            );
        }

        try {
            List<ImportRelationshipBootstrapper.ChapterSource> chapterSources = importedChapters.stream()
                    .map(ref -> new ImportRelationshipBootstrapper.ChapterSource(
                            ref.chapterId(),
                            ref.title(),
                            ref.content()
                    ))
                    .toList();
            ImportRelationshipBootstrapper.BootstrapResult bootstrap =
                    importRelationshipBootstrapper.build(chapterSources, summaries, characterCards);

            graphIndexStorage.saveCharacterAliasIndex(projectId, bootstrap.aliasIndex());
            graphIndexStorage.saveChapterMentionsIndex(projectId, bootstrap.chapterMentionIndex());
            graphIndexStorage.saveRelationCandidatesIndex(projectId, bootstrap.relationCandidateIndex());
            mergeGeneratedAliasesIntoCards(projectId, characterCards, bootstrap.generatedStrongAliases());

            Map<String, Object> response = new LinkedHashMap<>(bootstrap.stats());
            response.put("built", true);
            return response;
        } catch (Exception e) {
            log.warn("初始化关系索引失败: projectId={}, error={}", projectId, e.getMessage());
            return Map.of(
                    "built", false,
                    "error", e.getMessage()
            );
        }
    }

    private void mergeGeneratedAliasesIntoCards(String projectId,
                                                List<CharacterCard> existingCards,
                                                Map<String, List<String>> generatedAliases) {
        if (generatedAliases == null || generatedAliases.isEmpty() || existingCards == null || existingCards.isEmpty()) {
            return;
        }
        for (CharacterCard card : existingCards) {
            if (card == null || card.getName() == null || card.getName().isBlank()) {
                continue;
            }
            List<String> extras = generatedAliases.getOrDefault(card.getName(), List.of());
            if (extras.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            if (card.getAliases() != null) {
                card.getAliases().stream()
                        .filter(alias -> alias != null && !alias.isBlank())
                        .map(String::trim)
                        .forEach(merged::add);
            }
            extras.stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .map(String::trim)
                    .filter(alias -> !alias.equals(card.getName()))
                    .forEach(merged::add);
            if (card.getAliases() != null && merged.size() == card.getAliases().size()) {
                continue;
            }
            card.setAliases(new ArrayList<>(merged));
            try {
                cardStorage.saveCharacterCard(projectId, card);
            } catch (IOException e) {
                log.warn("写回角色卡别名失败: {} - {}", card.getName(), e.getMessage());
            }
        }
    }

    private Map<String, Object> extractCardsFromSummaries(List<ChapterSummary> summaries,
                                                          int maxCharacterCards,
                                                          int maxWorldCards) {
        CardCandidateBundle candidateBundle = buildCardCandidateBundle(summaries, maxCharacterCards, maxWorldCards);
        String material = buildCardSummaryMaterial(summaries, candidateBundle, maxCharacterCards, maxWorldCards);
        if (!material.isBlank()) {
            String systemPrompt = "你是一位小说资产初始化助手，擅长从章节摘要中提炼对续写/改写最有价值的角色卡和世界卡。";
            String userPrompt = "请基于以下章节摘要，为导入书籍快速初始化卡片。"
                    + "严格输出 JSON，不要任何解释。格式：\n"
                    + "{\n"
                    + "  \"characters\": [{\"name\":\"角色名\",\"description\":\"40-120字描述\",\"aliases\":[],\"stars\":2}],\n"
                    + "  \"world\": [{\"name\":\"设定名\",\"description\":\"40-120字描述\",\"aliases\":[],\"category\":\"location/organization/race/item/concept\",\"rules\":[],\"stars\":2}]\n"
                    + "}\n\n"
                    + "限制：\n"
                    + "- characters 最多 " + maxCharacterCards + " 个，只保留跨章节反复出现、对后续创作最有用的核心角色\n"
                    + "- world 最多 " + maxWorldCards + " 个，只保留跨章节稳定存在的重要设定/地点/组织/功法/规则\n"
                    + "- 不要把一次性情节事件当作卡片\n"
                    + "- 只要是“人物”，无论是本名还是带称谓的叫法（如刘局、王总、李警官、刘小姐），都必须放进 characters，不要放进 world\n"
                    + "- 不要把多个人物合并成一张卡，禁止使用“/”“／”“、”“和”等方式把两个人名写在同一 name 里\n"
                    + "- 父女、母子、上下级、情侣等关系人物，应该拆成各自独立的角色卡；relationship 只能写进 description，不能合并 name\n"
                    + "- 信息不足时宁可少给，也不要臆造\n\n"
                    + "材料：\n" + material;
            try {
                LlmResponse response = llmGateway.chat(
                        List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        ),
                        llmGateway.getProviderForAgent("extractor"),
                        null,
                        1800
                );
                Map<String, Object> parsed = parseCardsResponse(response.getContent(), maxCharacterCards, maxWorldCards);
                Map<String, Object> corrected = correctImportedCardCategories(parsed, candidateBundle, maxCharacterCards, maxWorldCards);
                Map<String, Object> merged = mergeCandidateResults(corrected, candidateBundle, maxCharacterCards, maxWorldCards);
                if (!((List<?>) merged.getOrDefault("characters", List.of())).isEmpty()
                        || !((List<?>) merged.getOrDefault("world", List.of())).isEmpty()) {
                    return merged;
                }
            } catch (Exception e) {
                log.warn("导入 cards LLM 提取失败，回退到启发式: {}", e.getMessage());
            }
        }
        return heuristicCards(candidateBundle, maxCharacterCards, maxWorldCards);
    }

    private String buildCardSummaryMaterial(List<ChapterSummary> summaries,
                                            CardCandidateBundle candidateBundle,
                                            int maxCharacterCards,
                                            int maxWorldCards) {
        StringBuilder sb = new StringBuilder();
        if (candidateBundle != null) {
            List<Map<String, Object>> topCharacters = limitMaps(candidateBundle.characterCards(), Math.min(maxCharacterCards, 6));
            List<Map<String, Object>> topWorld = limitMaps(candidateBundle.worldCards(), Math.min(maxWorldCards, 6));
            if (!topCharacters.isEmpty()) {
                sb.append("高置信候选角色: ")
                        .append(topCharacters.stream()
                                .map(item -> stringValue(item, "name"))
                                .filter(name -> !name.isBlank())
                                .collect(Collectors.joining("、")))
                        .append('\n');
            }
            if (!topWorld.isEmpty()) {
                sb.append("高置信候选设定: ")
                        .append(topWorld.stream()
                                .map(item -> stringValue(item, "name"))
                                .filter(name -> !name.isBlank())
                                .collect(Collectors.joining("、")))
                        .append('\n');
            }
            if (!topCharacters.isEmpty() || !topWorld.isEmpty()) {
                sb.append('\n');
            }
        }
        for (ChapterSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            sb.append(summary.getChapter()).append(' ')
                    .append(firstNonBlank(summary.getTitle(), summary.getChapter()))
                    .append('\n');
            if (summary.getBriefSummary() != null && !summary.getBriefSummary().isBlank()) {
                sb.append("摘要: ").append(summary.getBriefSummary()).append('\n');
            }
            if (summary.getKeyEvents() != null && !summary.getKeyEvents().isEmpty()) {
                sb.append("事件: ")
                        .append(summary.getKeyEvents().stream().limit(3).collect(Collectors.joining("；")))
                        .append('\n');
            }
            if (summary.getNewFacts() != null && !summary.getNewFacts().isEmpty()) {
                sb.append("事实: ")
                        .append(summary.getNewFacts().stream().limit(3).collect(Collectors.joining("；")))
                        .append('\n');
            }
            sb.append('\n');
            if (sb.length() >= CARD_SUMMARY_CONTEXT_LIMIT) {
                break;
            }
        }
        return sb.length() > CARD_SUMMARY_CONTEXT_LIMIT
                ? sb.substring(0, CARD_SUMMARY_CONTEXT_LIMIT) + "..."
                : sb.toString();
    }

    private Map<String, Object> parseCardsResponse(String content, int maxCharacterCards, int maxWorldCards) {
        String json = extractJson(content);
        if (json == null || json.isBlank()) {
            return Map.of("characters", List.of(), "world", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Map<String, Object>> characters = normalizeCardArray(root.path("characters"), "character", maxCharacterCards);
            List<Map<String, Object>> world = normalizeCardArray(root.path("world"), "world", maxWorldCards);
            return Map.of("characters", characters, "world", world);
        } catch (Exception e) {
            log.warn("解析导入 cards 响应失败: {}", e.getMessage());
            return Map.of("characters", List.of(), "world", List.of());
        }
    }

    private Map<String, Object> correctImportedCardCategories(Map<String, Object> parsed,
                                                              CardCandidateBundle bundle,
                                                              int maxCharacterCards,
                                                              int maxWorldCards) {
        List<Map<String, Object>> correctedCharacters = new ArrayList<>(castCardList(parsed.get("characters")));
        List<Map<String, Object>> correctedWorld = new ArrayList<>();
        LinkedHashSet<String> seenCharacterKeys = correctedCharacters.stream()
                .map(item -> normalizeKey(stringValue(item, "name")))
                .filter(key -> !key.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, EntityCandidate> characterSupport = bundle != null ? bundle.characterSupport() : Map.of();
        Map<String, EntityCandidate> worldSupport = bundle != null ? bundle.worldSupport() : Map.of();

        for (Map<String, Object> item : castCardList(parsed.get("world"))) {
            if (!looksLikeHumanWorldCard(item, characterSupport)) {
                String worldName = stringValue(item, "name").trim();
                EntityCandidate worldCandidate = worldSupport.get(normalizeKey(worldName));
                if (!isAcceptableWorldCardName(worldName, worldCandidate)) {
                    continue;
                }
                correctedWorld.add(item);
                continue;
            }
            for (Map<String, Object> migrated : migrateWorldCardToCharacters(item, characterSupport)) {
                String key = normalizeKey(stringValue(migrated, "name"));
                if (key.isBlank() || !seenCharacterKeys.add(key)) {
                    continue;
                }
                correctedCharacters.add(migrated);
                if (correctedCharacters.size() >= maxCharacterCards) {
                    break;
                }
            }
        }

        return Map.of(
                "characters", limitMaps(correctedCharacters, maxCharacterCards),
                "world", limitMaps(correctedWorld, maxWorldCards)
        );
    }

    private List<Map<String, Object>> normalizeCardArray(JsonNode node, String type, int limit) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            String name = textValue(item, "name");
            if (name.isBlank()) {
                continue;
            }
            String key = normalizeKey(name);
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("name", name);
            mapped.put("description", textValue(item, "description"));
            mapped.put("aliases", extractJsonArray(item.path("aliases")));
            mapped.put("stars", clampStars(intValue(item.path("stars").isMissingNode() ? null : item.path("stars").asInt(1), 1)));
            if ("world".equals(type)) {
                mapped.put("category", firstNonBlank(textValue(item, "category"), "concept"));
                mapped.put("rules", extractJsonArray(item.path("rules")));
            }
            result.add(mapped);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> heuristicCards(CardCandidateBundle bundle,
                                               int maxCharacterCards,
                                               int maxWorldCards) {
        List<Map<String, Object>> characters = limitMaps(bundle != null ? bundle.characterCards() : List.of(), maxCharacterCards);
        List<Map<String, Object>> world = limitMaps(bundle != null ? bundle.worldCards() : List.of(), maxWorldCards);
        return Map.of("characters", characters, "world", world);
    }

    private List<ExcerptCandidate> selectRepresentativeExcerpts(List<ImportedChapterRef> chapters, int limit) {
        List<ExcerptCandidate> candidates = new ArrayList<>();
        for (ImportedChapterRef chapter : chapters) {
            List<String> windows = buildExcerptWindows(chapter.content());
            for (String window : windows) {
                double score = scoreExcerpt(window);
                if (score <= 0) {
                    continue;
                }
                candidates.add(new ExcerptCandidate(chapter.chapterId(), window, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(ExcerptCandidate::score).reversed());

        List<ExcerptCandidate> selected = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        Map<String, List<ExcerptCandidate>> byChapter = candidates.stream()
                .collect(Collectors.groupingBy(ExcerptCandidate::chapterId, LinkedHashMap::new, Collectors.toList()));
        for (List<ExcerptCandidate> chapterCandidates : byChapter.values()) {
            if (chapterCandidates == null || chapterCandidates.isEmpty()) {
                continue;
            }
            ExcerptCandidate candidate = chapterCandidates.get(0);
            String textKey = normalizeKey(candidate.text());
            if (seenTexts.add(textKey)) {
                selected.add(candidate);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        for (ExcerptCandidate candidate : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            String textKey = normalizeKey(candidate.text());
            if (!seenTexts.add(textKey)) {
                continue;
            }
            selected.add(candidate);
        }
        if (selected.isEmpty()) {
            ExcerptCandidate fallback = selectFallbackExcerpt(chapters);
            if (fallback != null) {
                selected.add(fallback);
            }
        }
        return selected;
    }

    private double scoreExcerpt(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double score = 0;
        int len = text.length();
        if (len >= 90 && len <= 220) {
            score += 2.5d;
        } else if (len >= 70 && len <= 260) {
            score += 1.6d;
        }
        if (text.contains("“") || text.contains("”") || text.contains("\"")) {
            score += 0.8d;
        }
        if (text.contains("，") && (text.contains("。") || text.contains("！") || text.contains("？"))) {
            score += 0.7d;
        }
        long sentenceEndings = text.chars().filter(ch -> ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?').count();
        score += Math.min(1.4d, sentenceEndings * 0.4d);
        long uniqueChars = text.chars().distinct().count();
        score += Math.min(1.2d, uniqueChars / Math.max(1.0d, len) * 2.0d);
        for (String hint : STYLE_NARRATIVE_HINTS) {
            if (text.contains(hint)) {
                score += 0.15d;
            }
        }
        for (String phrase : STYLE_META_PHRASES) {
            if (text.contains(phrase)) {
                score -= 1.2d;
            }
        }
        if (text.startsWith("本章") || text.startsWith("这一章") || text.startsWith("总结") || text.startsWith("概括")) {
            score -= 2.2d;
        }
        if (looksLikeHeading(text)) {
            score -= 2.5d;
        }
        return score;
    }

    private List<String> splitParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = Arrays.stream(content.replace("\r\n", "\n").replace('\r', '\n').split("\\n{2,}"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (!paragraphs.isEmpty()) {
            return paragraphs;
        }
        return Arrays.stream(content.split("(?<=[。！？!?])"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String normalizeExcerpt(String text) {
        return text == null ? "" : text.replace("\r", "").replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private ExcerptCandidate selectFallbackExcerpt(List<ImportedChapterRef> chapters) {
        ExcerptCandidate best = null;
        for (ImportedChapterRef chapter : chapters) {
            if (chapter == null || chapter.content() == null || chapter.content().isBlank()) {
                continue;
            }
            for (String paragraph : splitParagraphs(chapter.content())) {
                String normalized = normalizeExcerpt(paragraph);
                if (normalized.isBlank()) {
                    continue;
                }
                if (normalized.length() > STYLE_EXCERPT_MAX) {
                    normalized = normalized.substring(0, STYLE_EXCERPT_MAX) + "…";
                }
                double score = scoreExcerpt(normalized);
                if (best == null || score > best.score()) {
                    best = new ExcerptCandidate(chapter.chapterId(), normalized, score);
                }
            }
        }
        if (best != null) {
            return best;
        }
        for (ImportedChapterRef chapter : chapters) {
            if (chapter == null) {
                continue;
            }
            String normalized = normalizeExcerpt(chapter.content());
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() > STYLE_EXCERPT_MAX) {
                normalized = normalized.substring(0, STYLE_EXCERPT_MAX) + "…";
            }
            return new ExcerptCandidate(chapter.chapterId(), normalized, scoreExcerpt(normalized));
        }
        return null;
    }

    private List<String> buildExcerptWindows(String content) {
        List<String> windows = new ArrayList<>();
        for (String paragraph : splitParagraphs(content)) {
            String normalized = normalizeExcerpt(paragraph);
            if (normalized.length() < STYLE_EXCERPT_MIN) {
                continue;
            }
            if (normalized.length() <= STYLE_EXCERPT_MAX) {
                windows.add(normalized);
                continue;
            }
            windows.addAll(sliceParagraphIntoWindows(normalized));
        }
        return windows;
    }

    private List<String> sliceParagraphIntoWindows(String paragraph) {
        List<String> windows = new ArrayList<>();
        List<String> sentences = Arrays.stream(paragraph.split("(?<=[。！？!?])"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (sentences.isEmpty()) {
            String clipped = paragraph.length() > STYLE_EXCERPT_MAX
                    ? paragraph.substring(0, STYLE_EXCERPT_MAX) + "…"
                    : paragraph;
            return clipped.length() >= STYLE_EXCERPT_MIN ? List.of(clipped) : List.of();
        }
        for (int i = 0; i < sentences.size(); i++) {
            StringBuilder current = new StringBuilder();
            for (int j = i; j < sentences.size() && j < i + 3; j++) {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(sentences.get(j));
                String built = current.toString().trim();
                if (built.length() >= STYLE_EXCERPT_MIN && built.length() <= STYLE_EXCERPT_MAX) {
                    windows.add(built);
                } else if (built.length() > STYLE_EXCERPT_MAX) {
                    break;
                }
            }
        }
        return windows;
    }

    private boolean looksLikeVolumeHeading(String line) {
        return line != null && line.length() <= 40 && VOLUME_HEADING.matcher(line).matches();
    }

    private boolean looksLikeChapterHeading(String line) {
        return line != null && line.length() <= 40 && CHAPTER_HEADING.matcher(line).matches();
    }

    private boolean looksLikeHeading(String line) {
        return looksLikeVolumeHeading(line) || looksLikeChapterHeading(line);
    }

    private boolean looksLikeWorldToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (WORLD_SUFFIX_STOP.contains(token)) {
            return true;
        }
        return WORLD_TOKEN.matcher(token).matches();
    }

    private String inferWorldCategory(String name) {
        if (name.endsWith("宗") || name.endsWith("门") || name.endsWith("派") || name.endsWith("会") || name.endsWith("盟") || name.endsWith("学院") || name.endsWith("组织")) {
            return "organization";
        }
        if (name.endsWith("城") || name.endsWith("山") || name.endsWith("谷") || name.endsWith("界") || name.endsWith("国") || name.endsWith("域") || name.endsWith("洲")) {
            return "location";
        }
        if (name.endsWith("族")) {
            return "race";
        }
        if (name.endsWith("诀") || name.endsWith("功") || name.endsWith("术") || name.endsWith("法")) {
            return "concept";
        }
        return "concept";
    }

    private ParsedBook parseBook(String filename, String contentType, byte[] bytes) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub") || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("epub"))) {
            return parseEpubBook(filename, bytes);
        }
        String text = decodeText(bytes);
        return parseTextBook(filename, text);
    }

    private ParsedBook parseEpubBook(String sourceName, byte[] bytes) throws IOException {
        Map<String, byte[]> entries = readZipEntries(bytes);
        String rootPath = resolveRootOpf(entries);
        if (rootPath == null || rootPath.isBlank()) {
            throw new IOException("epub 缺少 OPF 元数据，无法解析");
        }
        byte[] opfBytes = entries.get(rootPath);
        if (opfBytes == null) {
            throw new IOException("无法读取 epub OPF 文件: " + rootPath);
        }
        Document opf = Jsoup.parse(decodeText(opfBytes), "", Parser.xmlParser());
        Map<String, String> manifest = new HashMap<>();
        for (Element item : opf.select("manifest > item")) {
            String id = item.attr("id");
            String href = item.attr("href");
            if (!id.isBlank() && !href.isBlank()) {
                manifest.put(id, resolveEpubPath(rootPath, href));
            }
        }

        List<String> spineItems = new ArrayList<>();
        for (Element itemref : opf.select("spine > itemref")) {
            String idref = itemref.attr("idref");
            if (!idref.isBlank() && manifest.containsKey(idref)) {
                spineItems.add(manifest.get(idref));
            }
        }
        if (spineItems.isEmpty()) {
            spineItems = manifest.values().stream()
                    .filter(this::looksLikeHtmlEntry)
                    .sorted()
                    .toList();
        }

        List<VolumeSeed> seeds = new ArrayList<>();
        VolumeSeed currentVolume = new VolumeSeed(defaultVolumeLabel(sourceName), false);
        seeds.add(currentVolume);
        List<String> warnings = new ArrayList<>();

        for (String itemPath : spineItems) {
            byte[] htmlBytes = entries.get(itemPath);
            if (htmlBytes == null || htmlBytes.length == 0 || !looksLikeHtmlEntry(itemPath)) {
                continue;
            }
            String html = decodeText(htmlBytes);
            Document doc = Jsoup.parse(html);
            String title = extractDocumentTitle(doc, baseName(itemPath));
            String text = extractDocumentText(doc);
            if (text.isBlank()) {
                continue;
            }
            String normalizedTitle = cleanHeading(title);
            if (NOISE_DOC_TITLES.contains(normalizedTitle.toLowerCase(Locale.ROOT)) || text.length() < 40) {
                continue;
            }
            if (looksLikeVolumeHeading(normalizedTitle) && text.length() < 250) {
                currentVolume = new VolumeSeed(normalizedTitle, true);
                seeds.add(currentVolume);
                continue;
            }
            currentVolume.chapters.add(new ChapterSeed(
                    normalizeChapterTitle(normalizedTitle, currentVolume.chapters.size() + 1, sourceName, baseName(sourceName)),
                    text,
                    false
            ));
        }

        List<ParsedVolume> volumes = seeds.stream()
                .filter(seed -> !seed.chapters.isEmpty())
                .map(seed -> new ParsedVolume(
                        firstNonBlank(seed.title, defaultVolumeLabel(sourceName)),
                        seed.explicit,
                        seed.chapters.stream().map(ch -> new ParsedChapter(ch.title, ch.content, ch.syntheticTitle)).toList()
                ))
                .toList();

        if (volumes.isEmpty()) {
            warnings.add("epub 未能按目录结构识别章节，已回退到纯文本切分");
            String allText = entries.entrySet().stream()
                    .filter(entry -> looksLikeHtmlEntry(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        try {
                            return extractDocumentText(Jsoup.parse(decodeText(entry.getValue())));
                        } catch (Exception ex) {
                            return "";
                        }
                    })
                    .collect(Collectors.joining("\n\n"));
            ParsedBook fallback = parseTextBook(sourceName, allText);
            return new ParsedBook("epub", sourceName, fallback.explicitVolumes(), fallback.volumes(), warnings);
        }

        boolean explicitVolumes = volumes.stream().anyMatch(ParsedVolume::explicit);
        return new ParsedBook("epub", sourceName, explicitVolumes, volumes, warnings);
    }

    private String extractDocumentTitle(Document doc, String fallback) {
        if (doc == null) {
            return fallback;
        }
        for (String selector : List.of("h1", "h2", "h3", "title")) {
            Element element = doc.selectFirst(selector);
            if (element != null) {
                String text = cleanHeading(element.text());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    private String extractDocumentText(Document doc) {
        if (doc == null || doc.body() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Elements blocks = doc.body().select("h1,h2,h3,h4,p,li,blockquote,div");
        Set<String> seen = new LinkedHashSet<>();
        for (Element block : blocks) {
            String text = block.text().replace('\u00A0', ' ').trim();
            if (text.isBlank()) {
                continue;
            }
            String key = normalizeKey(text);
            if (!seen.add(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        if (sb.isEmpty()) {
            return doc.body().text().trim();
        }
        return sb.toString();
    }

    private Map<String, byte[]> readZipEntries(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    private String resolveRootOpf(Map<String, byte[]> entries) {
        byte[] containerBytes = entries.get("META-INF/container.xml");
        if (containerBytes != null) {
            try {
                Document container = Jsoup.parse(decodeText(containerBytes), "", Parser.xmlParser());
                Element rootfile = container.selectFirst("rootfile[full-path]");
                if (rootfile != null) {
                    return rootfile.attr("full-path");
                }
            } catch (Exception e) {
                log.warn("解析 epub container.xml 失败: {}", e.getMessage());
            }
        }
        return entries.keySet().stream().filter(name -> name.endsWith(".opf")).sorted().findFirst().orElse(null);
    }

    private String resolveEpubPath(String rootPath, String href) {
        if (href == null || href.isBlank()) {
            return href;
        }
        int idx = rootPath.lastIndexOf('/');
        if (idx < 0) {
            return href;
        }
        String baseDir = rootPath.substring(0, idx + 1);
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : (baseDir + href).split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private boolean looksLikeHtmlEntry(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".xhtml") || lower.endsWith(".htm");
    }

    private String decodeText(byte[] bytes) throws IOException {
        if (bytes == null) {
            return "";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
        }
        Charset gb18030 = Charset.forName("GB18030");
        return gb18030.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private List<ParsedChapter> splitIntoSyntheticChapters(String text, String sourceName) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<ParsedChapter> chapters = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 1;
        for (String paragraph : normalized.split("\\n{2,}")) {
            String block = paragraph.trim();
            if (block.isBlank()) {
                continue;
            }
            if (current.length() + block.length() > TXT_SPLIT_MAX && current.length() >= TXT_SPLIT_TARGET) {
                chapters.add(new ParsedChapter(sourceName + "-" + index, current.toString().trim(), true));
                current.setLength(0);
                index++;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (!current.isEmpty()) {
            chapters.add(new ParsedChapter(sourceName + "-" + index, current.toString().trim(), true));
        }
        return chapters;
    }

    private List<ParsedChapter> splitByLargeBlankGaps(String text, String sourceName) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> blocks = Arrays.stream(normalized.split("\\n(?:\\s*\\n){2,}"))
                .map(String::trim)
                .filter(block -> !block.isBlank())
                .toList();
        if (blocks.size() < 2) {
            return List.of();
        }

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (current.isEmpty()) {
                current.append(block);
                continue;
            }
            if (current.length() < BLANK_GAP_CHAPTER_MIN || block.length() < BLANK_GAP_CHAPTER_MIN) {
                current.append("\n\n").append(block);
                continue;
            }
            merged.add(current.toString().trim());
            current.setLength(0);
            current.append(block);
        }
        if (!current.isEmpty()) {
            merged.add(current.toString().trim());
        }

        if (merged.size() < 2) {
            return List.of();
        }

        List<ParsedChapter> chapters = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            chapters.add(new ParsedChapter(sourceName + "-" + (i + 1), merged.get(i), true));
        }
        return chapters;
    }

    private void flushChapter(VolumeSeed volume, String title, StringBuilder buffer) {
        String content = buffer == null ? "" : buffer.toString().trim();
        if (content.isBlank()) {
            buffer.setLength(0);
            return;
        }
        String finalTitle = firstNonBlank(title, volume.chapters.isEmpty() ? "序章" : "第" + (volume.chapters.size() + 1) + "章");
        volume.chapters.add(new ChapterSeed(finalTitle, content, title == null || title.isBlank()));
        buffer.setLength(0);
    }

    private int determineExistingMaxVolume(String projectId) {
        int max = 0;
        for (String chapter : draftStorage.listChapters(projectId)) {
            max = Math.max(max, extractVolumeNumber(chapter));
        }
        for (ChapterSummary summary : draftStorage.listChapterSummaries(projectId, null)) {
            max = Math.max(max, extractVolumeNumber(summary.getChapter()));
        }
        List<Volume> volumes = volumeStorage.listVolumes(projectId);
        boolean onlyDefaultVolume = volumes.size() == 1 && "V1".equals(volumes.get(0).getId())
                && firstNonBlank(volumes.get(0).getTitle(), "Volume 1").equals("Volume 1")
                && (volumes.get(0).getSummary() == null || volumes.get(0).getSummary().isBlank());
        if (!onlyDefaultVolume) {
            for (Volume volume : volumes) {
                max = Math.max(max, parseVolumeNumber(volume.getId()));
            }
        }
        return max;
    }

    private int extractVolumeNumber(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("^V(\\d+)", Pattern.CASE_INSENSITIVE).matcher(chapterId.trim());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int parseVolumeNumber(String volumeId) {
        return extractVolumeNumber(volumeId);
    }

    private String resolveProjectDisplayName(String projectId, String filename) {
        return projectStorage.getProject(projectId)
                .map(project -> firstNonBlank(project.getName(), project.getId()))
                .filter(name -> !name.isBlank())
                .orElseGet(() -> {
                    String base = baseName(filename);
                    return base.isBlank() ? "导入书籍" : base;
                });
    }

    private String resolveImportedVolumeTitle(String filename, ParsedVolume volume, String targetVolumeId, boolean explicitVolumes, String projectDisplayName) {
        String parsedTitle = volume != null ? volume.title() : "";
        if (explicitVolumes) {
            return firstNonBlank(parsedTitle, "Volume " + parseVolumeNumber(targetVolumeId));
        }
        return firstNonBlank(
                (!"Volume 1".equalsIgnoreCase(parsedTitle) && !defaultVolumeLabel(filename).equals(parsedTitle)) ? parsedTitle : "",
                projectDisplayName,
                baseName(filename),
                "Volume " + parseVolumeNumber(targetVolumeId)
        );
    }

    private String normalizeImportedContent(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized;
    }

    private String normalizeChapterTitle(String title, int index, String sourceName, String projectDisplayName) {
        String normalized = dedupeLeadingChapterMarkers(cleanHeading(title));
        if (normalized.isBlank()) {
            return projectDisplayName + "-" + index;
        }
        if (isSyntheticFallbackTitle(normalized, sourceName, index)) {
            return projectDisplayName + "-" + index;
        }
        return normalized;
    }

    private String dedupeLeadingChapterMarkers(String title) {
        String normalized = cleanHeading(title);
        for (int i = 0; i < 4; i++) {
            String firstMarker = extractLeadingChapterMarker(normalized);
            if (firstMarker.isBlank()) {
                break;
            }
            String rest = cleanHeading(normalized.substring(firstMarker.length()));
            if (rest.isBlank()) {
                break;
            }
            String secondMarker = extractLeadingChapterMarker(rest);
            if (secondMarker.isBlank()) {
                break;
            }
            normalized = rest;
        }
        return normalized;
    }

    private String extractLeadingChapterMarker(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher cn = LEADING_CHAPTER_MARKER_PATTERN.matcher(text);
        if (cn.find()) {
            return cn.group();
        }
        Matcher en = LEADING_IMPORT_EN_CHAPTER_PATTERN.matcher(text);
        if (en.find()) {
            return en.group();
        }
        return "";
    }

    private boolean isSyntheticFallbackTitle(String title, String sourceName, int index) {
        String normalizedTitle = cleanHeading(title);
        String base = baseName(sourceName);
        if (normalizedTitle.isBlank() || base.isBlank()) {
            return false;
        }
        return normalizedTitle.equals(base + "-" + index)
                || normalizedTitle.equals(base + " - " + index)
                || normalizedTitle.equalsIgnoreCase(base + "_" + index);
    }

    private String cleanHeading(String line) {
        if (line == null) {
            return "";
        }
        return line.replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    private String defaultVolumeLabel(String sourceName) {
        String base = baseName(sourceName);
        return base.isBlank() ? "Volume 1" : base;
    }

    private String baseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "导入书籍";
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.trim();
    }

    private List<String> extractJsonArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String textValue(JsonNode root, String key) {
        if (root == null || key == null || key.isBlank() || !root.has(key) || root.get(key).isNull()) {
            return "";
        }
        return root.get(key).asText("").trim();
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int clampStars(int stars) {
        return Math.max(1, Math.min(3, stars));
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : item.toString().trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = value.toString().trim();
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，;；\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Set<Character> toCharSet(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<Character> result = new LinkedHashSet<>();
        for (int i = 0; i < text.length(); i++) {
            result.add(text.charAt(i));
        }
        return Set.copyOf(result);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private CardCandidateBundle buildCardCandidateBundle(List<ChapterSummary> summaries,
                                                         int maxCharacterCards,
                                                         int maxWorldCards) {
        Map<String, EntityCandidate> characterCandidates = new LinkedHashMap<>();
        Map<String, EntityCandidate> worldCandidates = new LinkedHashMap<>();
        int chapterIndex = 0;
        for (ChapterSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            chapterIndex++;
            String chapterRef = firstNonBlank(summary.getChapter(), "C" + chapterIndex);
            collectCardSignals(characterCandidates, worldCandidates, chapterRef,
                    firstNonBlank(summary.getTitle(), ""), 1.4d, "title");
            collectCardSignals(characterCandidates, worldCandidates, chapterRef,
                    firstNonBlank(summary.getBriefSummary(), ""), 1.9d, "brief");
            if (summary.getKeyEvents() != null) {
                for (String event : summary.getKeyEvents()) {
                    collectCardSignals(characterCandidates, worldCandidates, chapterRef, event, 1.5d, "event");
                }
            }
            if (summary.getNewFacts() != null) {
                for (String fact : summary.getNewFacts()) {
                    collectCardSignals(characterCandidates, worldCandidates, chapterRef, fact, 2.1d, "fact");
                }
            }
            if (summary.getCharacterStateChanges() != null) {
                for (String change : summary.getCharacterStateChanges()) {
                    collectCardSignals(characterCandidates, worldCandidates, chapterRef, change, 1.7d, "state");
                }
            }
            if (summary.getOpenLoops() != null) {
                for (String loop : summary.getOpenLoops()) {
                    collectCardSignals(characterCandidates, worldCandidates, chapterRef, loop, 1.2d, "loop");
                }
            }
        }

        List<Map<String, Object>> characters = selectCharacterCards(characterCandidates, maxCharacterCards);
        List<Map<String, Object>> world = selectWorldCards(worldCandidates, maxWorldCards);
        return new CardCandidateBundle(characters, world, characterCandidates, worldCandidates);
    }

    private void collectCardSignals(Map<String, EntityCandidate> characterCandidates,
                                    Map<String, EntityCandidate> worldCandidates,
                                    String chapterRef,
                                    String text,
                                    double weight,
                                    String field) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = normalizeExcerpt(text);
        Set<String> explicitWorldTokens = new LinkedHashSet<>();
        Matcher worldMatcher = WORLD_TOKEN.matcher(normalized);
        while (worldMatcher.find()) {
            String token = normalizeWorldCandidateToken(worldMatcher.group(1));
            if (token.isBlank()) {
                continue;
            }
            explicitWorldTokens.add(token);
            worldCandidates.computeIfAbsent(normalizeKey(token), ignored -> new EntityCandidate(token))
                    .bump(chapterRef, weight * 1.8d, normalized, field, 0.0d, 0.0d);
        }

        for (String token : extractCharacterNameCandidates(normalized, explicitWorldTokens)) {
            CharacterSignal signal = analyzeCharacterCandidateSignal(token, normalized);
            if (signal.rejected()) {
                continue;
            }
            double adjustedWeight = Math.max(0.35d, weight + signal.positiveScore() * 0.45d - signal.negativeScore() * 0.75d);
            characterCandidates.computeIfAbsent(normalizeKey(token), ignored -> new EntityCandidate(token))
                    .bump(chapterRef, adjustedWeight, normalized, field, signal.positiveScore(), signal.negativeScore());
        }
    }

    private List<String> extractCharacterNameCandidates(String text, Set<String> explicitWorldTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> results = new LinkedHashSet<>();
        for (String clause : text.split("[，。！？；：]")) {
            String trimmed = clause == null ? "" : clause.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String leading = extractLeadingCharacterCandidate(trimmed, explicitWorldTokens);
            if (!leading.isBlank()) {
                results.add(leading);
            }
            Matcher speech = SPEECH_NAME.matcher(trimmed);
            while (speech.find()) {
                String token = speech.group(1);
                if (isValidCharacterCandidate(token, trimmed, explicitWorldTokens)) {
                    results.add(token);
                }
            }
            results.addAll(extractInlineCharacterCandidates(trimmed, explicitWorldTokens));
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
            if (!isPlausibleLeadingCharacterName(candidate, leadingHan)) {
                continue;
            }
            return candidate;
        }
        return "";
    }

    private boolean isValidCharacterCandidate(String token, String clause, Set<String> explicitWorldTokens) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4) {
            return false;
        }
        if (!isLikelyStandaloneCharacterName(trimmed)) {
            return false;
        }
        if (conflictsWithExplicitWorldToken(trimmed, explicitWorldTokens)) {
            return false;
        }
        if (analyzeCharacterCandidateSignal(trimmed, clause).rejected()) {
            return false;
        }
        return clause == null || !clause.startsWith("本章" + trimmed);
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
                || WORLD_SUFFIX_STOP.contains(trimmed)) {
            return false;
        }
        if (looksLikeWorldToken(trimmed) || isLowValueWorldCandidate(trimmed)) {
            return false;
        }
        if (isTemporalNarrativeToken(trimmed)) {
            return false;
        }
        char first = trimmed.charAt(0);
        if ("这那其某每各该另再还并但却而因为所以如果虽然于是就在与及和被把将向从对".indexOf(first) >= 0) {
            return false;
        }
        return true;
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
            if (startsWithAny(right, CHARACTER_NEGATIVE_RIGHT_HINTS)) {
                negative += index == 0 ? 3.1d : 1.8d;
            }
            if (endsWithAny(left, CHARACTER_POSITIVE_LEFT_HINTS)) {
                positive += 2.2d;
            }
            if (endsWithAny(left, CHARACTER_NEGATIVE_LEFT_HINTS)) {
                negative += 2.1d;
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
                && (token.length() == 2 || SINGLE_CHINESE_SURNAMES.contains(token.charAt(1)));
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

    private boolean conflictsWithExplicitWorldToken(String token, Set<String> explicitWorldTokens) {
        if (token == null || token.isBlank() || explicitWorldTokens == null || explicitWorldTokens.isEmpty()) {
            return false;
        }
        for (String worldToken : explicitWorldTokens) {
            if (worldToken.startsWith(token) || token.startsWith(worldToken)) {
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

    private boolean isLowValueWorldCandidate(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || WORLD_SUFFIX_STOP.contains(trimmed)) {
            return true;
        }
        if (WORLD_ABSTRACT_STOPWORDS.contains(trimmed) || CHARACTER_STOPWORDS.contains(trimmed)) {
            return true;
        }
        if (hasSuspiciousWorldPrefix(trimmed) || containsSuspiciousWorldPhrase(trimmed)) {
            return true;
        }
        String body = stripWorldSuffix(trimmed);
        if (!body.isBlank() && containsTooManyWorldFunctionChars(body)) {
            return true;
        }
        return false;
    }

    private boolean isAcceptableWorldCardName(String name, EntityCandidate supportCandidate) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        if (isLowValueWorldCandidate(trimmed)) {
            return false;
        }
        if (supportCandidate != null) {
            return true;
        }
        return WORLD_TOKEN.matcher(trimmed).matches();
    }

    private List<Map<String, Object>> selectCharacterCards(Map<String, EntityCandidate> candidates, int limit) {
        List<EntityCandidate> ranked = candidates.values().stream()
                .filter(this::passesCharacterThreshold)
                .sorted(Comparator.comparingDouble(EntityCandidate::characterScore).reversed())
                .toList();
        List<EntityCandidate> effective = ranked.isEmpty()
                ? candidates.values().stream()
                .filter(this::passesLooseCharacterThreshold)
                .sorted(Comparator.comparingDouble(EntityCandidate::characterScore).reversed())
                .toList()
                : ranked;
        return effective.stream()
                .limit(limit)
                .map(this::toCharacterCardMap)
                .toList();
    }

    private List<Map<String, Object>> selectWorldCards(Map<String, EntityCandidate> candidates, int limit) {
        List<EntityCandidate> ranked = candidates.values().stream()
                .filter(this::passesWorldThreshold)
                .sorted(Comparator.comparingDouble(EntityCandidate::worldScore).reversed())
                .toList();
        return ranked.stream()
                .limit(limit)
                .map(this::toWorldCardMap)
                .toList();
    }

    private boolean passesCharacterThreshold(EntityCandidate candidate) {
        return candidate != null
                && !candidate.name.isBlank()
                && candidate.isPromptWorthy()
                && (candidate.coverage.size() >= 2 || candidate.mentions >= 3 || candidate.weight >= 4.5d);
    }

    private boolean passesLooseCharacterThreshold(EntityCandidate candidate) {
        return candidate != null
                && !candidate.name.isBlank()
                && isLikelyStandaloneCharacterName(candidate.name)
                && candidate.isPromptWorthy()
                && candidate.characterScore() >= 3.2d;
    }

    private boolean passesWorldThreshold(EntityCandidate candidate) {
        return candidate != null
                && !candidate.name.isBlank()
                && (candidate.coverage.size() >= 2 || candidate.mentions >= 2 || candidate.weight >= 4.0d);
    }

    private Map<String, Object> toCharacterCardMap(EntityCandidate candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", candidate.name);
        item.put("description", buildEntityDescription(candidate, true));
        item.put("aliases", List.of());
        item.put("stars", clampStars(candidate.coverage.size() >= 3 ? 3 : (candidate.coverage.size() >= 2 ? 2 : 1)));
        return item;
    }

    private Map<String, Object> toWorldCardMap(EntityCandidate candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", candidate.name);
        item.put("description", buildEntityDescription(candidate, false));
        item.put("aliases", List.of());
        item.put("category", inferWorldCategory(candidate.name));
        item.put("rules", List.of());
        item.put("stars", clampStars(candidate.coverage.size() >= 3 ? 3 : (candidate.coverage.size() >= 2 ? 2 : 1)));
        return item;
    }

    private String buildEntityDescription(EntityCandidate candidate, boolean character) {
        String prefix = character
                ? "在导入内容中跨" + candidate.coverage.size() + "章反复出现"
                : "在导入内容中跨" + candidate.coverage.size() + "章稳定出现";
        String snippet = candidate.snippets.stream()
                .map(this::trimCardSnippet)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(2)
                .collect(Collectors.joining("；"));
        String description = snippet.isBlank()
                ? prefix + "，建议后续结合正文继续补全。"
                : prefix + "，常见于：" + snippet;
        return description.length() > 120 ? description.substring(0, 118) + "…" : description;
    }

    private String trimCardSnippet(String text) {
        String normalized = normalizeExcerpt(text);
        if (normalized.length() > 36) {
            return normalized.substring(0, 36) + "…";
        }
        return normalized;
    }

    private Map<String, Object> mergeCandidateResults(Map<String, Object> parsed,
                                                      CardCandidateBundle bundle,
                                                      int maxCharacterCards,
                                                      int maxWorldCards) {
        List<Map<String, Object>> mergedCharacters = mergeCardCategory(
                castCardList(parsed.get("characters")),
                bundle != null ? bundle.characterCards() : List.of(),
                bundle != null ? bundle.characterSupport() : Map.of(),
                maxCharacterCards,
                true
        );
        List<Map<String, Object>> mergedWorld = mergeCardCategory(
                castCardList(parsed.get("world")),
                bundle != null ? bundle.worldCards() : List.of(),
                bundle != null ? bundle.worldSupport() : Map.of(),
                maxWorldCards,
                false
        );
        return Map.of("characters", mergedCharacters, "world", mergedWorld);
    }

    private List<Map<String, Object>> mergeCardCategory(List<Map<String, Object>> primary,
                                                        List<Map<String, Object>> fallback,
                                                        Map<String, EntityCandidate> support,
                                                        int limit,
                                                        boolean character) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> item : primary) {
            Map<String, Object> normalized = normalizeMergedCard(item, support, character);
            if (normalized == null) {
                continue;
            }
            merged.putIfAbsent(normalizeKey(stringValue(normalized, "name")), normalized);
            if (merged.size() >= limit) {
                break;
            }
        }
        for (Map<String, Object> item : fallback) {
            Map<String, Object> normalized = normalizeMergedCard(item, support, character);
            if (normalized == null) {
                continue;
            }
            merged.putIfAbsent(normalizeKey(stringValue(normalized, "name")), normalized);
            if (merged.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castCardList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> normalizeMergedCard(Map<String, Object> item,
                                                    Map<String, EntityCandidate> support,
                                                    boolean character) {
        String name = stringValue(item, "name").trim();
        if (name.isBlank()) {
            return null;
        }
        EntityCandidate candidate = null;
        if (character) {
            name = resolveCharacterName(name, support);
            if (!isLikelyStandaloneCharacterName(name)) {
                return null;
            }
            candidate = support.get(normalizeKey(name));
            if (candidate != null && !candidate.isPromptWorthy()) {
                return null;
            }
        } else {
            candidate = support.get(normalizeKey(name));
            if (!isAcceptableWorldCardName(name, candidate)) {
                return null;
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(item);
        normalized.put("name", name);
        if (stringValue(normalized, "description").isBlank() && candidate != null) {
            normalized.put("description", buildEntityDescription(candidate, character));
        }
        if (normalized.get("aliases") == null) {
            normalized.put("aliases", List.of());
        }
        int stars = intValue(normalized.get("stars"), candidate != null ? (candidate.coverage.size() >= 2 ? 2 : 1) : 1);
        normalized.put("stars", clampStars(stars));
        if (!character) {
            normalized.put("category", firstNonBlank(stringValue(normalized, "category"), inferWorldCategory(name)));
            if (normalized.get("rules") == null) {
                normalized.put("rules", List.of());
            }
        }
        return normalized;
    }

    private boolean looksLikeHumanWorldCard(Map<String, Object> item,
                                            Map<String, EntityCandidate> characterSupport) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        String name = stringValue(item, "name").trim();
        List<String> aliases = stringList(item.get("aliases"));
        String description = stringValue(item, "description");
        if (name.isBlank()) {
            return false;
        }
        if (isLooseHumanCardName(name, characterSupport)) {
            return true;
        }

        List<String> parts = splitHumanCardNameParts(name);
        long humanPartCount = parts.stream()
                .filter(part -> isLooseHumanCardName(part, characterSupport))
                .count();
        if (humanPartCount >= 2) {
            return true;
        }

        long humanAliasCount = aliases.stream()
                .filter(alias -> isLooseHumanCardName(alias, characterSupport))
                .count();
        if (humanAliasCount >= 2 && hasImportedCharacterDescriptionHint(description)) {
            return true;
        }
        return humanAliasCount >= 1 && hasImportedCharacterDescriptionHint(description) && name.contains("/");
    }

    private List<Map<String, Object>> migrateWorldCardToCharacters(Map<String, Object> item,
                                                                   Map<String, EntityCandidate> characterSupport) {
        LinkedHashMap<String, Map<String, Object>> migrated = new LinkedHashMap<>();
        List<String> names = extractMigratedCharacterNames(item, characterSupport);
        String description = stringValue(item, "description").trim();
        int stars = clampStars(intValue(item.get("stars"), 2));
        boolean multiName = names.size() > 1;

        for (String name : names) {
            String trimmedName = name == null ? "" : name.trim();
            if (trimmedName.isBlank()) {
                continue;
            }
            EntityCandidate candidate = characterSupport.get(normalizeKey(trimmedName));
            Map<String, Object> mapped = candidate != null
                    ? new LinkedHashMap<>(toCharacterCardMap(candidate))
                    : new LinkedHashMap<>();
            mapped.put("name", trimmedName);
            if (stringValue(mapped, "description").isBlank()) {
                if (!multiName && !description.isBlank()) {
                    mapped.put("description", description);
                } else {
                    mapped.put("description", "导入内容中的重要角色，建议后续结合章节继续补全。");
                }
            }
            if (mapped.get("aliases") == null) {
                mapped.put("aliases", List.of());
            }
            mapped.put("stars", clampStars(intValue(mapped.get("stars"), stars)));
            migrated.putIfAbsent(normalizeKey(trimmedName), mapped);
        }
        return new ArrayList<>(migrated.values());
    }

    private List<String> extractMigratedCharacterNames(Map<String, Object> item,
                                                       Map<String, EntityCandidate> characterSupport) {
        LinkedHashSet<String> preferred = new LinkedHashSet<>();
        String name = stringValue(item, "name");
        List<String> aliases = stringList(item.get("aliases"));

        for (String token : splitHumanCardNameParts(name)) {
            String resolved = resolveCharacterName(token, characterSupport);
            if (characterSupport.containsKey(normalizeKey(resolved)) && isCanonicalHumanCardName(resolved, characterSupport)) {
                preferred.add(resolved);
            }
        }
        for (String alias : aliases) {
            String resolved = resolveCharacterName(alias, characterSupport);
            if (characterSupport.containsKey(normalizeKey(resolved)) && isCanonicalHumanCardName(resolved, characterSupport)) {
                preferred.add(resolved);
            }
        }
        if (!preferred.isEmpty()) {
            return new ArrayList<>(preferred);
        }

        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        for (String token : splitHumanCardNameParts(name)) {
            String resolved = resolveCharacterName(token, characterSupport);
            if (isCanonicalHumanCardName(resolved, characterSupport)) {
                fallback.add(resolved);
            }
        }
        if (fallback.isEmpty()) {
            for (String alias : aliases) {
                String resolved = resolveCharacterName(alias, characterSupport);
                if (isCanonicalHumanCardName(resolved, characterSupport)) {
                    fallback.add(resolved);
                }
            }
        }
        if (fallback.isEmpty() && isCanonicalHumanCardName(name, characterSupport)) {
            fallback.add(resolveCharacterName(name, characterSupport));
        }
        return new ArrayList<>(fallback);
    }

    private List<String> splitHumanCardNameParts(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return List.of();
        }
        return HUMAN_CARD_NAME_SPLITTER.splitAsStream(rawName)
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private boolean isCanonicalHumanCardName(String token,
                                             Map<String, EntityCandidate> characterSupport) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        return characterSupport.containsKey(normalizeKey(trimmed))
                || isLikelyStandaloneCharacterName(trimmed)
                || matchesSurnameWithCharacterTitle(trimmed, false);
    }

    private boolean isLooseHumanCardName(String token,
                                         Map<String, EntityCandidate> characterSupport) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        return isCanonicalHumanCardName(trimmed, characterSupport)
                || matchesSurnameWithCharacterTitle(trimmed, true);
    }

    private boolean matchesSurnameWithCharacterTitle(String token, boolean includeWeakTitles) {
        if (token == null || token.isBlank() || !isAllHan(token)) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 2 || trimmed.length() > 4) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (!SINGLE_CHINESE_SURNAMES.contains(first)) {
            return false;
        }
        return STRONG_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(trimmed::endsWith)
                || (includeWeakTitles && WEAK_CHARACTER_TITLE_SUFFIXES.stream().anyMatch(trimmed::endsWith));
    }

    private boolean hasImportedCharacterDescriptionHint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        return IMPORT_CHARACTER_DESCRIPTION_HINTS.stream().anyMatch(normalized::contains);
    }

    private String resolveCharacterName(String rawName, Map<String, EntityCandidate> support) {
        if (rawName == null || rawName.isBlank() || support == null || support.isEmpty()) {
            return rawName;
        }
        EntityCandidate direct = support.get(normalizeKey(rawName));
        if (direct != null) {
            return direct.name;
        }
        EntityCandidate best = null;
        for (EntityCandidate candidate : support.values()) {
            if (candidate == null || candidate.name == null || candidate.name.isBlank()) {
                continue;
            }
            if (!candidate.name.startsWith(rawName) || candidate.name.length() <= rawName.length()) {
                continue;
            }
            if (candidate.name.length() > rawName.length() + 2) {
                continue;
            }
            if (!matchesChineseNameStructure(candidate.name)) {
                continue;
            }
            if (best == null || candidate.characterScore() > best.characterScore()) {
                best = candidate;
            }
        }
        return best != null ? best.name : rawName;
    }

    private List<Map<String, Object>> limitMaps(List<Map<String, Object>> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return List.of();
        }
        return items.size() <= limit ? new ArrayList<>(items) : new ArrayList<>(items.subList(0, limit));
    }

    private String normalizeWorldCandidateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String trimmed = rawToken.trim();
        String suffix = matchedWorldSuffix(trimmed);
        if (suffix == null) {
            return "";
        }
        String best = "";
        for (int start = 0; start < trimmed.length(); start++) {
            String candidate = trimmed.substring(start);
            if (candidate.length() <= suffix.length() || candidate.length() > 12) {
                continue;
            }
            if (!candidate.endsWith(suffix) || !WORLD_TOKEN.matcher(candidate).matches()) {
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

    Map<String, Object> heuristicCardsForTesting(List<ChapterSummary> summaries,
                                                 int maxCharacterCards,
                                                 int maxWorldCards) {
        return heuristicCards(buildCardCandidateBundle(summaries, maxCharacterCards, maxWorldCards), maxCharacterCards, maxWorldCards);
    }

    Map<String, Object> correctImportedCardsForTesting(Map<String, Object> parsed,
                                                       List<ChapterSummary> summaries,
                                                       int maxCharacterCards,
                                                       int maxWorldCards) {
        return correctImportedCardCategories(
                parsed,
                buildCardCandidateBundle(summaries, maxCharacterCards, maxWorldCards),
                maxCharacterCards,
                maxWorldCards
        );
    }

    Map<String, Object> finalizeImportedCardsForTesting(Map<String, Object> parsed,
                                                        List<ChapterSummary> summaries,
                                                        int maxCharacterCards,
                                                        int maxWorldCards) {
        CardCandidateBundle bundle = buildCardCandidateBundle(summaries, maxCharacterCards, maxWorldCards);
        Map<String, Object> corrected = correctImportedCardCategories(parsed, bundle, maxCharacterCards, maxWorldCards);
        return mergeCandidateResults(corrected, bundle, maxCharacterCards, maxWorldCards);
    }

    String normalizeImportedChapterTitleForTesting(String title, int index, String sourceName, String projectDisplayName) {
        return normalizeChapterTitle(title, index, sourceName, projectDisplayName);
    }

    List<String> representativeExcerptsForTesting(List<String> contents, int limit) {
        List<ImportedChapterRef> refs = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            refs.add(new ImportedChapterRef("V1C" + (i + 1), "V1", "C" + (i + 1), contents.get(i), i));
        }
        return selectRepresentativeExcerpts(refs, limit).stream().map(ExcerptCandidate::text).toList();
    }

    public record ParsedBook(String format,
                              String sourceName,
                              boolean explicitVolumes,
                              List<ParsedVolume> volumes,
                              List<String> warnings) {
        int chapterCount() {
            return volumes == null ? 0 : volumes.stream().mapToInt(volume -> volume.chapters() != null ? volume.chapters().size() : 0).sum();
        }
    }

    public record ParsedVolume(String title, boolean explicit, List<ParsedChapter> chapters) {
    }

    public record ParsedChapter(String title, String content, boolean syntheticTitle) {
    }

    private record TitleExtractionPreparation(ParsedBook book, boolean enabled, int candidateCount, int replacedCount) {
    }

    private record SyntheticTitleCandidate(int volumeIndex, int chapterIndex, ParsedChapter chapter) {
        private String key() {
            return volumeIndex + ":" + chapterIndex;
        }
    }

    private record ImportedChapterRef(String chapterId, String volumeId, String title, String content, int orderIndex) {
    }

    private record ExcerptCandidate(String chapterId, String text, double score) {
    }

    private static final class CandidateScore {
        private final String name;
        private final Set<String> coverage = new LinkedHashSet<>();
        private double weight;

        private CandidateScore(String name) {
            this.name = name;
        }

        private void bump(String chapterRef, double delta) {
            if (chapterRef != null && !chapterRef.isBlank()) {
                coverage.add(chapterRef);
            }
            weight += delta;
        }

        private double score() {
            return coverage.size() * 3.0d + weight;
        }
    }

    private static final class EntityCandidate {
        private final String name;
        private final Set<String> coverage = new LinkedHashSet<>();
        private final Set<String> fields = new LinkedHashSet<>();
        private final List<String> snippets = new ArrayList<>();
        private int mentions;
        private double weight;
        private double positiveSignals;
        private double negativeSignals;

        private EntityCandidate(String name) {
            this.name = name;
        }

        private void bump(String chapterRef,
                          double delta,
                          String snippet,
                          String field,
                          double positiveSignal,
                          double negativeSignal) {
            if (chapterRef != null && !chapterRef.isBlank()) {
                coverage.add(chapterRef);
            }
            if (field != null && !field.isBlank()) {
                fields.add(field);
            }
            mentions++;
            weight += delta;
            positiveSignals += Math.max(0.0d, positiveSignal);
            negativeSignals += Math.max(0.0d, negativeSignal);
            String normalizedSnippet = snippet == null ? "" : snippet.trim();
            if (!normalizedSnippet.isBlank()) {
                String key = normalizedSnippet.length() > 80 ? normalizedSnippet.substring(0, 80) : normalizedSnippet;
                boolean exists = snippets.stream().anyMatch(existing -> existing.equals(key) || normalizeInline(existing).equals(normalizeInline(key)));
                if (!exists) {
                    snippets.add(key);
                }
            }
        }

        private double characterScore() {
            return coverage.size() * 4.8d
                    + Math.min(mentions, 8) * 1.1d
                    + weight
                    + Math.min(fields.size(), 4) * 0.7d
                    + positiveSignals * 0.9d
                    - negativeSignals * 1.35d;
        }

        private double worldScore() {
            return coverage.size() * 4.2d + Math.min(mentions, 8) * 0.9d + weight + Math.min(fields.size(), 4) * 0.8d;
        }

        private boolean isPromptWorthy() {
            if (negativeSignals >= positiveSignals + 2.2d) {
                return false;
            }
            return !(negativeSignals >= 3.4d && positiveSignals < 1.0d);
        }

        private String normalizeInline(String text) {
            return text == null ? "" : text.replaceAll("\\s+", "");
        }
    }

    private record CharacterSignal(double positiveScore, double negativeScore, boolean rejected) {
    }

    private record CardCandidateBundle(List<Map<String, Object>> characterCards,
                                       List<Map<String, Object>> worldCards,
                                       Map<String, EntityCandidate> characterSupport,
                                       Map<String, EntityCandidate> worldSupport) {
    }

    private static final class VolumeSeed {
        private final String title;
        private final boolean explicit;
        private final List<ChapterSeed> chapters = new ArrayList<>();

        private VolumeSeed(String title, boolean explicit) {
            this.title = title;
            this.explicit = explicit;
        }
    }

    private static final class ChapterSeed {
        private final String title;
        private final String content;
        private final boolean syntheticTitle;

        private ChapterSeed(String title, String content, boolean syntheticTitle) {
            this.title = title;
            this.content = content;
            this.syntheticTitle = syntheticTitle;
        }
    }
}
