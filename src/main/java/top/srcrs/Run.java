package top.srcrs;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;
import java.util.*;

/**
 * 程序執行開始的地方
 * @author srcrs
 * @Time 2020-10-31
 */
public class Run
{
    /** 獲取日誌記錄器對象 */
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /** 獲取使用者所有關注貼吧 */
    String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    /** 獲取使用者的tbs */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /** 貼吧簽到接口 */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    /** 儲存使用者所關注的貼吧 */
    private List<String> follow = new ArrayList<>();
    /** 簽到成功的貼吧列表 */
    private static List<String>  success = new ArrayList<>();
    /** 使用者的tbs */
    private String tbs = "";
    /** 使用者所關注的貼吧數量 */
    private static Integer followNum = 201;
    public static void main( String[] args ){
        Cookie cookie = Cookie.getInstance();
        // 存入Cookie，以備使用
        if(args.length==0){
            LOGGER.warn("請在Secrets中填寫BDUSS");
        }
        cookie.setBDUSS(args[0]);
        Run run = new Run();
        run.getTbs();
        run.getFollow();
        run.runSign();
        LOGGER.info("共 {} 個貼吧 - 成功: {} - 失敗: {}",followNum,success.size(),followNum-success.size());
        if(args.length == 2){
            run.send(args[1]);
        }
    }

    /**
     * 進行登錄，獲得 tbs ，簽到的時候需要用到這個參數
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getTbs(){
        try{
            JSONObject jsonObject = Request.get(TBS_URL);
            if("1".equals(jsonObject.getString("is_login"))){
                LOGGER.info("獲取tbs成功");
                tbs = jsonObject.getString("tbs");
            } else{
                LOGGER.warn("獲取tbs失敗 -- " + jsonObject);
            }
        } catch (Exception e){
            LOGGER.error("獲取tbs部分出現錯誤 -- " + e);
        }
    }

    /**
     * 獲取使用者所關注的貼吧列表
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow(){
        try{
            JSONObject jsonObject = Request.get(LIKE_URL);
            LOGGER.info("獲取貼吧列表成功");
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
            followNum = jsonArray.size();
            // 獲取使用者所有關注的貼吧
            for (Object array : jsonArray) {
                if("0".equals(((JSONObject) array).getString("is_sign"))){
                    // 將為簽到的貼吧加入到 follow 中，待簽到
                    follow.add(((JSONObject) array).getString("forum_name").replace("+","%2B"));
                } else{
                    // 將已經成功簽到的貼吧，加入到 success
                    success.add(((JSONObject) array).getString("forum_name"));
                }
            }
        } catch (Exception e){
            LOGGER.error("獲取貼吧列表部分出現錯誤 -- " + e);
        }
    }

    /**
     * 開始進行簽到，每一輪性將所有未簽到的貼吧進行簽到，一共進行5輪，如果還未簽到完就立即結束
     * 一般一次只會有少數的貼吧未能完成簽到，為了減少接口訪問次數，每一輪簽到完等待1分鐘，如果在過程中所有貼吧簽到完則結束。
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign(){
        // 當執行 5 輪所有貼吧還未簽到成功就結束操作
        Integer flag = 5;
        try{
            while(success.size()<followNum&&flag>0){
                LOGGER.info("-----第 {} 輪簽到開始-----", 5 - flag + 1);
                LOGGER.info("還剩 {} 貼吧需要簽到", followNum - success.size());
                Iterator<String> iterator = follow.iterator();
                while(iterator.hasNext()){
                    String s = iterator.next();
                    String rotation = s.replace("%2B","+");
                    String body = "kw="+s+"&tbs="+tbs+"&sign="+ Encryption.enCodeMd5("kw="+rotation+"tbs="+tbs+"tiebaclient!!!");
                    JSONObject post = Request.post(SIGN_URL, body);
                    if("0".equals(post.getString("error_code"))){
                        Integer sign_bonus_point = post.getJSONObject("user_info").getInteger("sign_bonus_point");
                        Integer user_sign_rank = post.getJSONObject("user_info").getInteger("user_sign_rank");
                        Integer cont_sign_num = jsonObject.getJsonObject("user_info").getInteger("cont_sign_num");
                        Integer total_sign_num = jsonObject.getJsonObject("user_info").getInteger("total_sign_num");
                        Integer miss_sign_num = jsonObject.getJsonObject("user_info").getInteger("miss_sign_num");
                        iterator.remove();
                        success.add(rotation);
                        LOGGER.info(rotation + ": " + "簽到成功，經驗 +{}，今日本吧第 {} 個簽到，連續簽到：{}天，累計簽到：{}天，漏簽：{}天", sign_bonus_point, user_sign_rank, cont_sign_num, total_sign_num, miss_sign_num);
                    } else {
                        LOGGER.warn(rotation + ": " + "簽到失敗");
                    }
                }
                if (success.size() != followNum){
                    // 為防止短時間內多次請求接口，觸發風控，設定每一輪簽到完等待 5 分鐘
                    Thread.sleep(1000 * 60 * 5);
                    /**
                     * 重新獲取 tbs
                     * 嘗試解決以前第 1 次簽到失敗，賸餘 4 次循環都會失敗的錯誤。
                     */
                    getTbs();
                }
                flag--;
            }
        } catch (Exception e){
            LOGGER.error("簽到部分出現錯誤 -- " + e);
        }
    }

    /**
     * 發送執行結果到微信，通過 server 醬
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
    public void send(String sckey){
        /** 將要推送的資料 */
        String text = "總: "+ followNum + " - ";
        text += "成功: " + success.size() + " 失敗: " + (followNum - success.size());
        String desp = "共 "+ followNum + " 貼吧\n\n";
        desp += "成功: " + success.size() + " 失敗: " + (followNum - success.size());
        String body = "text="+text+"&desp="+"TiebaSignIn執行結果\n\n"+desp;
        StringEntity entityBody = new StringEntity(body,"UTF-8");
        HttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://sc.ftqq.com/"+sckey+".send");
        httpPost.addHeader("Content-Type","application/x-www-form-urlencoded");
        httpPost.setEntity(entityBody);
        HttpResponse resp = null;
        String respContent = null;
        try{
            resp = client.execute(httpPost);
            HttpEntity entity=null;
            if(resp.getStatusLine().getStatusCode()<400){
                entity = resp.getEntity();
            } else{
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
            LOGGER.info("server醬推送正常");
        } catch (Exception e){
            LOGGER.error("server醬發送失敗 -- " + e);
        }
    }
}
