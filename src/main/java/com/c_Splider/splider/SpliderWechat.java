package com.c_Splider.splider;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.b_util.basicUtil.a_DBUtil;
import com.c_Splider.Splider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.*;
import java.util.*;

import static com.b_util.HttpClientHelper.*;
import static com.b_util.basicUtil.b_PropertiesLoadUtil.loadProperties;
import static com.b_util.basicUtil.b_PropertiesLoadUtil.loadPropertiesGetSetciontoMap;

public class SpliderWechat implements Splider {
    private Properties prop = loadProperties("config.properties");
    private String savePicpathPrefxi = prop.getProperty("savepicpath_prefxi");

    public String getPictureAndUrl(String picsrc, String i) {
        String picurl = "img/wechat/" + i + ".jpg";
        sendGetToGetPicture(picsrc, null, savePicpathPrefxi + picurl);
        return picurl;
    }

    public List<String> getLasttimeArticleTitle() {
        List<String> alltitles = new LinkedList();
        Connection conn = a_DBUtil.getConnection();
        try {
            String sql = "select title from article_info";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String title = rs.getString("title");
                alltitles.add(title);
                System.out.println(title);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return alltitles;
    }


    @Override
    public void resultToMysql(String datasource, JSONArray htmlstr) {
        Connection conn = a_DBUtil.getConnection();
        try {
            Statement deleteStatement = conn.createStatement();
            String deleteSql = "DELETE FROM article_info_v2 WHERE article_source= \"" + datasource + "\"";
            deleteStatement.execute(deleteSql);

            conn.setAutoCommit(false);
            String sql = "INSERT INTO article_info_v2 "
                    + "(article_source,title,trueurl,picurl,abstract) "
                    + "VALUES "
                    + "(?,?,?,?,?)";
            PreparedStatement ps = conn.prepareStatement(sql);
            for (Object i : htmlstr) {
                JSONObject jsonObject = (JSONObject) i;
                ps.setString(1, datasource);
                ps.setString(2, jsonObject.getString("article"));
                ps.setString(3, jsonObject.getString("trueurl"));
                ps.setString(4, jsonObject.getString("picpath"));
                ps.setString(5, jsonObject.getString("introduction"));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                conn.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println("入库完毕");
    }

    @Override
    public JSONArray execute(String urlname) {
        JSONArray jsonArray = new JSONArray();
        String urls = null;
        try {
            urls = URLEncoder.encode(urlname, "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        String url1 = "https://weixin.sogou.com/weixin?type=1&s_from=input&query=" + urls;
        Map<String, String> requestHeaders = loadPropertiesGetSetciontoMap("config.properties", "basic-requestheader");

        //第一次访问获取cookie以及url
        Map<String, String> resultUrl1 = sendGet(url1, requestHeaders);
        String responseContext = resultUrl1.get("responseContext");
        String cookie = resultUrl1.get("responseCookie");

        Document doc = Jsoup.parse(responseContext);
        Element ele = doc.getElementById("sogou_vr_11002301_box_0");
        Elements eles = ele.getElementsByTag("a");
        String linkUrl = eles.last().attr("href");
        String article = eles.last().text();
        linkUrl = linkUrl.replace("&amp;", "&");

        int a = linkUrl.indexOf("url=");
        int c = linkUrl.indexOf("&k=");
        int b = (int) Math.random() * 100;
        if (-1 != a && -1 == c) {
            String check = linkUrl.substring(a + 4 + 21 + b, a + 4 + 21 + b + 1);
            linkUrl = linkUrl + "&k=" + b + "&h=" + check;
        }
        linkUrl = "https://weixin.sogou.com" + linkUrl;

        requestHeaders.put("Cookie", cookie);
        requestHeaders.put("Referer", url1);

        //第二次带cookie访问
        Map<String, String> resultUrl2 = sendGet(linkUrl, requestHeaders);
        String spliturlr = resultUrl2.get("responseContext");
        String trueUrl = "";
        String[] spsstr = spliturlr.split(";");
        for (int i = 1; i < spsstr.length - 2; i++) {
            trueUrl = trueUrl + spsstr[i].substring(spsstr[i].indexOf("'") + 1, spsstr[i].lastIndexOf("'"));
        }

        //第三次通过搜索的方式获取更多信息
        String title = "";
        try {
            title = URLEncoder.encode(article, "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        String t = "https://weixin.sogou.com/weixin?type=2&s_from=input&query=" + title;
        Map<String, String> resultUrl3 = sendGet(t, requestHeaders);
        String searchInfo = resultUrl3.get("responseContext");

        Document doc3 = Jsoup.parse(searchInfo);
        String pichref = "http:" + doc3.getElementById("sogou_vr_11002601_img_0").getElementsByTag("img").first().attr("src");
        String picpath = getPictureAndUrl(pichref, System.currentTimeMillis() + "");

        String introduction = doc3.getElementById("sogou_vr_11002601_summary_0").text();

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("picpath", picpath);
        jsonObject.put("introduction", introduction);
        jsonObject.put("article", article);
        jsonObject.put("trueurl", trueUrl);
        jsonArray.add(jsonObject);

        return jsonArray;
    }


    @Override
    public void startSplider() {
        Properties prop = loadProperties("config.properties");
        String allArticle = prop.getProperty("all_article");
        String[] wechatNames = allArticle.split(",");
        for (int i = 0; i < wechatNames.length; i++) {
            String wechatName = wechatNames[i];
            System.out.println(wechatName);
            JSONArray result = execute(wechatName);
            try {
                Thread.sleep(6 * 5 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if ("".equals(result)) {
                System.out.println(wechatName + "抓取失败");
                continue;
            }
            resultToMysql(wechatName, result);
        }
    }


    public static void main(String[] args) {
        SpliderWechat sw = new SpliderWechat();
        sw.startSplider();
    }
}