package com.moxu.rewrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic span repair, not a factual or general semantic validator. */
public final class NumberGuard {
    private NumberGuard() {}

    public record Result(String text, List<Map<String, String>> repairs,
                         List<String> warnings, boolean preservedOriginal) {
        public Result {
            repairs = repairs.stream().map(Map::copyOf).toList();
            warnings = List.copyOf(warnings);
        }
    }

    private static final String DIGIT = "0-9０-９";
    private static final String HAN = "零〇○一二两兩三四五六七八九十廿卅卌百千万萬亿億兆壹贰貳叁參肆伍陆陸柒捌玖拾佰仟";
    private static final String MAGNITUDE = "十拾百佰千仟万萬亿億兆";
    private static final String DECIMAL = "[" + DIGIT + "]+(?:[.．][" + DIGIT + "]+)?";
    // Do not merge a name's Chinese digit with the following Arabic amount (李四300元).
    private static final String ATOM = "(?:(?:[" + DIGIT + "]{1,3}(?:[,，][" + DIGIT
            + "]{3})+(?:[.．][" + DIGIT + "]+)?|" + DECIMAL + ")(?:[" + MAGNITUDE
            + "]+" + DECIMAL + ")*[" + MAGNITUDE + "]*|[" + HAN
            + "]+(?:[点點][" + DIGIT + HAN + "]+)?)";
    private static final String SIGN = "[+＋\\-－−±∓负負正<>＜＞≤≥≈~～]?";
    private static final String NUM = SIGN + ATOM;
    // Long units precede their prefixes. A changed unit belongs to the repaired span.
    private static final String UNIT = "(?:平方米|平方厘米|平方公里|立方米|千瓦时|摄氏度|华氏度|"
            + "人民币|美元|欧元|港元|日元|公斤|千克|毫克|公里|千米|厘米|毫米|毫升|"
            + "万元|千元|亿元|元整|块钱|小时|分钟|秒钟|号房|房间|百分比|"
            + "km/h|m/s|kWh|MHz|GHz|kPa|MPa|mm|cm|km|kg|mg|ml|Hz|GB|MB|"
            + "°[CF]|℃|℉|[%％‰‱]|[元圆圓块角分年月日号號点點时時秒米克吨升度岁歲个個"
            + "人名位件张張把条條本册冊辆輛艘架枚颗顆粒只隻匹头頭次遍倍成折章节節页頁层層楼樓室房天周套箱瓶杯碗寸尺丈里斤两兩钱錢]|[mgsL])";
    private static final String CLOCK = "(?:凌晨|上午|中午|下午|晚上|早上)?" + ATOM
            + "(?:[:：]" + ATOM + "(?:[:：]" + ATOM + ")?|[点點时時](?:半|"
            + ATOM + "分?(?:" + ATOM + "秒)?)?)(?:\\s*(?:AM|PM))?";
    private static final String DATE = "(?:[" + DIGIT + "]{4}[-－/.．／][" + DIGIT
            + "]{1,2}[-－/.．／][" + DIGIT + "]{1,2}|" + ATOM + "年" + ATOM
            + "月(?:" + ATOM + "[日号號])?|" + ATOM + "月" + ATOM + "[日号號])";
    private static final Pattern VALUE = Pattern.compile(
            DATE + "(?:[ T　]*" + CLOCK + ")?|" + CLOCK + "(?![" + DIGIT + HAN + "]|" + UNIT + ")|"
            + "(?:第|[A-Za-z]{1,4}[-－]?)?(?:[¥￥$＄€£])?" + SIGN
            + "(?:[¥￥$＄€£])?(?:百分之|千分之|万分之)?" + ATOM
            + "(?:分之" + NUM + "|[/／]" + NUM + "|[-－—–至到]" + NUM + ")?"
            + "(?:\\s*" + UNIT + "(?:" + NUM + UNIT + ")*)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ARABIC = Pattern.compile("[" + DIGIT + "]");
    private static final Pattern BARE_HAN = Pattern.compile("[" + HAN + "]+");
    private static final Pattern SCALE = Pattern.compile("[十拾百佰千仟万萬亿億兆]");
    private static final Pattern CUE = Pattern.compile(
            "(?:编号|編號|房号|房號|号码|數量|数量|数值|答案|等于|为|是|共|记下了?|記下了?|"
                    + "记录了?|記錄了?|写下了?|寫下了?|填入了?|填了?|报出了?|報出了?|数到了?|數到了?)$");
    private static final Pattern BREAK = Pattern.compile("[，,。！？!?；;：:\\n\\r]");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{P}　]+");
    // An explicit, small set of manner modifiers before actions is safe to ignore
    // for alignment. Arbitrary edit distance/subsequences can hide changed names or 不.
    private static final Pattern MANNER = Pattern.compile(
            "(?:悄悄|轻轻|缓缓|默默|慢慢|静静)地?(?=递|给|交|付|还|拿|放|走|说|问|答|看|带|取|收|买|卖|读|写|推|拉)");
    private static final List<String> IDIOMS = List.of("万一", "萬一", "一抹", "一无所获", "一無所獲",
            "一时", "一時", "一成不变", "一成不變", "一分为二", "一分為二", "千方百计", "千方百計",
            "千军万马", "千軍萬馬", "三心二意", "四面八方", "七上八下", "九死一生", "晴空万里", "晴空萬里");
    private record Span(int start, int end, String value, String left, String right) {}
    private record Pair(Span original, Span generated) {}

    public static Result protect(String original, String generated) {
        // Null output is a failed generation, never a reason to lose the selection.
        if (original == null || generated == null) {
            return fallback(original, "输入或生成文本为空，无法定位数值，保留原选区。");
        }
        if (Objects.equals(original, generated)) {
            return new Result(generated, List.of(), List.of(), false);
        }
        List<Span> before = spans(original);
        List<Span> after = spans(generated);
        // Exact expression/order equality says nothing about other semantics; it
        // deliberately allows prose changes without attempting context alignment.
        if (before.stream().map(Span::value).toList().equals(after.stream().map(Span::value).toList())) {
            return new Result(generated, List.of(), List.of(), false);
        }
        if (before.size() != after.size()) {
            String reason = before.size() > after.size() ? "数字遗漏" : "数字新增或重复";
            return fallback(original, reason + "：原文 " + before.size() + " 处，生成文 "
                    + after.size() + " 处，无法安全逐项恢复，保留原选区。");
        }
        List<Pair> pairs = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Span source : before) {
            // Never use numeric value or occurrence order to break an alignment tie.
            List<Span> exact = after.stream().filter(target -> exactContext(source, target)).toList();
            List<Span> candidates = exact.isEmpty()
                    ? after.stream().filter(target -> compatibleContext(source, target)).toList() : exact;
            if (candidates.isEmpty()) {
                return fallback(original, "数字上下文无法定位（可能存在人物、动作或句式变化）："
                        + context(source) + "，保留原选区。");
            }
            if (candidates.size() != 1 || !used.add(candidates.getFirst().start())) {
                return fallback(original, "数字上下文多解或重复，无法唯一对应："
                        + context(source) + "，保留原选区。");
            }
            pairs.add(new Pair(source, candidates.getFirst()));
        }
        // All matches must be a bijection before making any change (no partial repair).
        pairs.sort(Comparator.comparingInt(pair -> pair.generated().start()));
        List<Map<String, String>> repairs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        for (Pair pair : pairs) {
            Span source = pair.original();
            Span target = pair.generated();
            text.append(generated, cursor, target.start()).append(source.value());
            cursor = target.end();
            if (!source.value().equals(target.value())) {
                repairs.add(Map.of("original", source.value(), "generated", target.value(),
                        "context", context(target)));
            }
        }
        text.append(generated, cursor, generated.length());
        return new Result(text.toString(), repairs, List.of(), false);
    }

    private static Result fallback(String original, String warning) {
        return new Result(original, List.of(), List.of(warning), true);
    }

    private static List<Span> spans(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = VALUE.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if (isNumeric(text, matcher.start(), value)) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        List<Span> result = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i)[1];
            int leftBound = i == 0 ? 0 : ranges.get(i - 1)[1];
            int rightBound = i + 1 == ranges.size() ? text.length() : ranges.get(i + 1)[0];
            String left = text.substring(leftBound, start);
            Matcher boundary = BREAK.matcher(left);
            int cut = 0;
            while (boundary.find()) cut = boundary.end();
            left = left.substring(cut);
            String right = text.substring(end, rightBound);
            boundary = BREAK.matcher(right);
            if (boundary.find()) right = right.substring(0, boundary.start());
            // Keep the entire clause: clipping can discard the actor before an amount.
            result.add(new Span(start, end, text.substring(start, end), clean(left), clean(right)));
        }
        return result;
    }

    private static boolean isNumeric(String text, int start, String value) {
        if (ARABIC.matcher(value).find()) return true;
        for (String idiom : IDIOMS) {
            int from = Math.max(0, start - idiom.length());
            int index = text.indexOf(idiom, from);
            if (index >= 0 && index <= start && start < index + idiom.length()) return false;
        }
        if (!BARE_HAN.matcher(value).matches()) return true; // explicit unit, sign or fraction
        if (SCALE.matcher(value).find() && value.length() > 1) return true;
        return CUE.matcher(text.substring(Math.max(0, start - 8), start)).find();
    }

    private static String clean(String text) {
        return SPACE.matcher(text).replaceAll("");
    }

    private static boolean exactContext(Span a, Span b) {
        return (!a.left().isEmpty() || !a.right().isEmpty())
                && a.left().equals(b.left()) && a.right().equals(b.right());
    }

    private static boolean compatibleContext(Span a, Span b) {
        return (!a.left().isEmpty() || !a.right().isEmpty())
                && compatibleSide(a.left(), b.left()) && compatibleSide(a.right(), b.right());
    }

    private static boolean compatibleSide(String a, String b) {
        return MANNER.matcher(a).replaceAll("").equals(MANNER.matcher(b).replaceAll(""));
    }

    private static String context(Span span) {
        return span.left() + "【" + span.value() + "】" + span.right();
    }
}
