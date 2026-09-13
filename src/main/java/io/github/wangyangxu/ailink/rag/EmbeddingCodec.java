package io.github.wangyangxu.ailink.rag;

/**
 * 向量编解码 —— 数据库里以 JSON 数组文本存储（可读、可审计、换库时可直接迁移），
 * 进程内以 float[] 参与计算。
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {}

    public static String encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    /** 解析失败或空值返回 null —— 调用方按「该片段只有关键词通道可用」处理。 */
    public static float[] decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String body = json.trim();
            if (body.startsWith("[")) {
                body = body.substring(1);
            }
            if (body.endsWith("]")) {
                body = body.substring(0, body.length() - 1);
            }
            if (body.isBlank()) {
                return new float[0];
            }
            String[] parts = body.split(",");
            float[] vector = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }
            return vector;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 余弦相似度；向量已在建索引/查询时做过 L2 归一化，因此等价于点积。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0d;
        }
        double dot = 0d;
        double na = 0d;
        double nb = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0d || nb == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static void normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        double sum = 0d;
        for (float v : vector) {
            sum += v * v;
        }
        if (sum == 0d) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}