package cn.chuyu.reports;

import cn.chuyu.shared.Json;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded-memory XLSX. User strings stay text; safe numeric values retain number types. */
public final class StreamXlsx {
  private static final Map<String, String> HEADERS =
      Map.ofEntries(
          Map.entry("id", "记录编号"),
          Map.entry("pointId", "会员点位"),
          Map.entry("shopId", "代办店铺"),
          Map.entry("payerId", "付款点位"),
          Map.entry("productId", "商品编号"),
          Map.entry("orderType", "购买类型"),
          Map.entry("quantity", "瓶数"),
          Map.entry("unitPrice", "单价（元）"),
          Map.entry("total", "订单金额（元）"),
          Map.entry("rewardPaid", "奖励积分支付（元）"),
          Map.entry("cashPaid", "现金部分（元）"),
          Map.entry("paymentStatus", "支付状态"),
          Map.entry("activationStatus", "激活状态"),
          Map.entry("fulfilmentStatus", "发货状态"),
          Map.entry("cancelled", "已撤销"),
          Map.entry("pickup", "自提"),
          Map.entry("ruleId", "规则版本"),
          Map.entry("createdAt", "创建时间"),
          Map.entry("activatedAt", "激活时间"),
          Map.entry("walletKind", "积分类型"),
          Map.entry("delta", "变动金额（元）"),
          Map.entry("balanceAfter", "变动后余额（元）"),
          Map.entry("frozenAfter", "冻结金额（元）"),
          Map.entry("transactionId", "流水编号"),
          Map.entry("description", "业务说明"),
          Map.entry("originId", "来源编号"),
          Map.entry("businessKind", "业务类型"),
          Map.entry("recipientId", "受益点位"),
          Map.entry("awardType", "奖金类型"),
          Map.entry("gross", "毛奖（元）"),
          Map.entry("fee", "费用（元）"),
          Map.entry("net", "实得（元）"),
          Map.entry("baseAmount", "计算基数"),
          Map.entry("rate", "比例"),
          Map.entry("explanation", "计算依据"),
          Map.entry("reversed", "已冲回"),
          Map.entry("nickname", "昵称"),
          Map.entry("phone", "手机号"),
          Map.entry("ownerId", "账户归属"),
          Map.entry("parentId", "推荐点位"),
          Map.entry("grade", "会员星级"),
          Map.entry("directorGrade", "董事星级"),
          Map.entry("shopType", "店铺类型"),
          Map.entry("own", "个人业绩（元）"),
          Map.entry("team", "团队业绩（元）"),
          Map.entry("small", "小市场业绩（元）"),
          Map.entry("amount", "申请金额（元）"),
          Map.entry("status", "状态"),
          Map.entry("bankMask", "银行卡尾号"),
          Map.entry("tax", "税费（元）"),
          Map.entry("reason", "原因"),
          Map.entry("period", "账期"),
          Map.entry("periodType", "结算类型"),
          Map.entry("postedAt", "入账时间"),
          Map.entry("actorId", "操作人"),
          Map.entry("action", "操作"),
          Map.entry("targetId", "目标编号"));

  private StreamXlsx() {}

  private static String esc(Object o) {
    return String.valueOf(o == null ? "" : o)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
  }

  private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static void text(Writer w, Object value) throws IOException {
    w.write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">");
    w.write(esc(value));
    w.write("</t></is></c>");
  }

  public static void write(Iterable<Map<String, Object>> rows, OutputStream output)
      throws IOException {
    try (var zip = new ZipOutputStream(output)) {
      entry(
          zip,
          "[Content_Types].xml",
          "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
              + " Extension=\"rels\""
              + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
              + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
              + " PartName=\"/xl/workbook.xml\""
              + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
              + " PartName=\"/xl/worksheets/sheet1.xml\""
              + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
      entry(
          zip,
          "_rels/.rels",
          "<Relationships"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
              + " Id=\"rId1\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
              + " Target=\"xl/workbook.xml\"/></Relationships>");
      entry(
          zip,
          "xl/workbook.xml",
          "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
              + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
              + " name=\"业务明细\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      entry(
          zip,
          "xl/_rels/workbook.xml.rels",
          "<Relationships"
              + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
              + " Id=\"rId1\""
              + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
              + " Target=\"worksheets/sheet1.xml\"/></Relationships>");
      Iterator<Map<String, Object>> iterator = rows.iterator();
      Map<String, Object> row = iterator.hasNext() ? iterator.next() : null;
      List<String> keys = row == null ? List.of("查询结果") : new ArrayList<>(row.keySet());
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      var w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 65536);
      w.write(
          "<worksheet"
              + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row>");
      for (String key : keys) text(w, HEADERS.getOrDefault(key, key));
      w.write("</row>");
      while (row != null) {
        w.write("<row>");
        for (String key : keys) {
          Object v = row.get(key);
          if (v instanceof Number n && (!(n instanceof BigDecimal b) || b.precision() <= 15)) {
            w.write("<c><v>");
            w.write(n instanceof BigDecimal b ? b.toPlainString() : n.toString());
            w.write("</v></c>");
          } else text(w, v instanceof Map || v instanceof Collection ? Json.write(v) : v);
        }
        w.write("</row>");
        row = iterator.hasNext() ? iterator.next() : null;
      }
      w.write("</sheetData></worksheet>");
      w.flush();
      zip.closeEntry();
      zip.finish();
    }
  }
}
