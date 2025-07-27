package org.dromara.recognize.client.aliyunspeed;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.xml.bind.DatatypeConverter;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.recognize.client.RecognizeClient;
import org.dromara.recognize.utils.OkHttpUtils;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;


@RequiredArgsConstructor
@Slf4j
@Service
public class AliyunSpeedClientImpl implements RecognizeClient {

    private static final String applicationKey = System.getenv("ALIYUN_APP_KEY");

    private final OkHttpClient aliYunHttpClient;

    @Data
    static class QualityTestingRecognizeProvider {
        String appKey;
        String appSecret;
        String url;
        String callBackUrl;

        QualityTestingRecognizeProvider(String appKey, String appSecret, String url, String callBackUrl) {
            this.appKey = appKey;
            this.appSecret = appSecret;
            this.url = url;
            this.callBackUrl = callBackUrl;
        }
    }

    private final QualityTestingRecognizeProvider qp = new QualityTestingRecognizeProvider(
            System.getenv("ALIYUN_ACCESS_KEY_ID"),
            System.getenv("ALIYUN_ACCESS_KEY_SECRET"),
            "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/FlashRecognizer",
            "");

    @Override
    public String doRecognize(String fileUrl) {
        String response = null;
        try {
            String token = createOrGetToken(qp.appKey, qp.appSecret);
            String appkey = applicationKey;

            //String fileName = SpeechRecognizerRestfulDemo.class.getClassLoader().getResource("./nls-sample-16k.wav").getPath();
            // 重要：此处用一个本地文件来模拟发送实时流数据，实际使用时，您可以从某处实时采集或接收语音流并发送到ASR服务端。
            String fileName = URLEncoder.encode(fileUrl, "UTF-8");
            String format = "wav";
            int sampleRate = 16000;
            /**
             * 设置HTTPS REST POST请求
             * 1.使用http协议
             * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com
             * 3.语音识别接口请求路径：/stream/v1/FlashRecognizer
             * 4.设置必须请求参数：appkey、token、format、sample_rate
             */
            String url = qp.url;
            String request = url;
            request = request + "?appkey=" + appkey;
            request = request + "&token=" + token;
            request = request + "&format=" + format;
            request = request + "&sample_rate=" + sampleRate;

            HashMap<String, String> headers = new HashMap<String, String>();
            long start = System.currentTimeMillis();
            headers.put("Content-Type", "application/text");
            response = sendPostLink(request, headers, fileName, aliYunHttpClient);
            System.out.println("latency = " + (System.currentTimeMillis() - start) + " ms");
            if (response != null) {
                System.out.println("Response: " + response);
            }
            else {
                System.err.println("识别失败!");
            }
        } catch (Exception e){

        }
        return response;
    }


    private String createOrGetToken(String accessKeyId, String accessKeySecret){
        String cacheToken = RedisUtils.getCacheObject("token");
        if (StringUtils.isNotEmpty(cacheToken)){
            log.info("redis取出token:{}", cacheToken);
            return cacheToken;
        }else{
            System.out.println(getISO8601Time(null));
            // 所有请求参数
            Map<String, String> queryParamsMap = new HashMap<String, String>();
            queryParamsMap.put("AccessKeyId", accessKeyId);
            queryParamsMap.put("Action", "CreateToken");
            queryParamsMap.put("Version", "2019-02-28");
            queryParamsMap.put("Timestamp", getISO8601Time(null));
            queryParamsMap.put("Format", "JSON");
            queryParamsMap.put("RegionId", "cn-shanghai");
            queryParamsMap.put("SignatureMethod", "HMAC-SHA1");
            queryParamsMap.put("SignatureVersion", "1.0");
            queryParamsMap.put("SignatureNonce", getUniqueNonce());
            /**
             * 1.构造规范化的请求字符串
             */
            String queryString = canonicalizedQuery(queryParamsMap);
            if (null == queryString) {
                System.out.println("构造规范化的请求字符串失败！");
                return null;
            }
            /**
             * 2.构造签名字符串
             */
            String method = "GET";  // 发送请求的 HTTP 方法，GET
            String urlPath = "/";   // 请求路径
            String stringToSign = createStringToSign(method, urlPath, queryString);
            if (null == stringToSign) {
                System.out.println("构造签名字符串失败");
                return null;
            }
            /**
             * 3.计算签名
             */
            String signature = sign(stringToSign, accessKeySecret + "&");
            if (null == signature) {
                System.out.println("计算签名失败!");
                return null;
            }
            /**
             * 4.将签名加入到第1步获取的请求字符串
             */
            String queryStringWithSign = "Signature=" + signature + "&" + queryString;
            System.out.println("带有签名的请求字符串：" + queryStringWithSign);
            /**
             * 5.发送HTTP GET请求，获取token。
             */
            /**
             * 设置HTTP GET请求
             * 1. 使用HTTP协议
             * 2. Token服务域名：nls-meta.cn-shanghai.aliyuncs.com
             * 3. 请求路径：/
             * 4. 设置请求参数
             */
            String url = "http://nls-meta.cn-shanghai.aliyuncs.com";
            url = url + "/";
            url = url + "?" + queryStringWithSign;
            System.out.println("HTTP请求链接：" + url);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response response =  OkHttpUtils.execute(request, aliYunHttpClient);){

//                OkHttpClient client = new OkHttpClient();
//                Response response = client.newCall(request).execute();
                String result = response.body().string();
                if (response.isSuccessful()) {
                    JSONObject rootObj = JSON.parseObject(result);
                    JSONObject tokenObj = rootObj.getJSONObject("Token");
                    if (tokenObj != null) {
                        String token = tokenObj.getString("Id");
                        expireTime = tokenObj.getLongValue("ExpireTime");
                        //设置缓存
                        if (null != token) {
                            RedisUtils.setCacheObject("token", token);
                            System.out.println("获取的Token：" + token + ", 有效期时间戳（秒）：" + expireTime);
                            // 将10位数的时间戳转换为北京时间
                            String expireDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expireTime * 1000));
                            System.out.println("Token有效期的北京时间：" + expireDate);
                         }
                        return token;
                    }
                    else{
                        System.err.println("提交获取Token请求失败: " + result);
                    }
                }
                else {
                    System.err.println("提交获取Token请求失败: " + result);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return "";
    }


    private final static String TIME_ZONE = "GMT";
    private final static String FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private final static String URL_ENCODING = "UTF-8";
    private static final String ALGORITHM_NAME = "HmacSHA1";
    private static final String ENCODING = "UTF-8";
    private static long expireTime = 0;

    /**
     * 获取时间戳
     * 必须符合ISO8601规范，并需要使用UTC时间，时区为+0。
     */
    private String getISO8601Time(Date date) {
        Date nowDate = date;
        if (null == date) {
            nowDate = new Date();
        }
        SimpleDateFormat df = new SimpleDateFormat(FORMAT_ISO8601);
        df.setTimeZone(new SimpleTimeZone(0, TIME_ZONE));
        return df.format(nowDate);
    }
    /**
     * 获取UUID
     */
    private String getUniqueNonce() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString();
    }
    /**
     * URL编码
     * 使用UTF-8字符集按照RFC3986规则编码请求参数和参数取值。
     */
    private String percentEncode(String value) throws UnsupportedEncodingException {
        return value != null ? URLEncoder.encode(value, URL_ENCODING).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~") : null;
    }
    /***
     * 将参数排序后，进行规范化设置，组合成请求字符串。
     * @param queryParamsMap   所有请求参数
     * @return 规范化的请求字符串
     */
    private String canonicalizedQuery( Map<String, String> queryParamsMap) {
        String[] sortedKeys = queryParamsMap.keySet().toArray(new String[] {});
        Arrays.sort(sortedKeys);
        String queryString = null;
        try {
            StringBuilder canonicalizedQueryString = new StringBuilder();
            for (String key : sortedKeys) {
                canonicalizedQueryString.append("&")
                        .append(percentEncode(key)).append("=")
                        .append(percentEncode(queryParamsMap.get(key)));
            }
            queryString = canonicalizedQueryString.toString().substring(1);
            System.out.println("规范化后的请求参数串：" + queryString);
        } catch (UnsupportedEncodingException e) {
            System.out.println("UTF-8 encoding is not supported.");
            e.printStackTrace();
        }
        return queryString;
    }
    /***
     * 构造签名字符串
     * @param method       HTTP请求的方法
     * @param urlPath      HTTP请求的资源路径
     * @param queryString  规范化的请求字符串
     * @return 签名字符串
     */
    public String createStringToSign(String method, String urlPath, String queryString) {
        String stringToSign = null;
        try {
            StringBuilder strBuilderSign = new StringBuilder();
            strBuilderSign.append(method);
            strBuilderSign.append("&");
            strBuilderSign.append(percentEncode(urlPath));
            strBuilderSign.append("&");
            strBuilderSign.append(percentEncode(queryString));
            stringToSign = strBuilderSign.toString();
            System.out.println("构造的签名字符串：" + stringToSign);
        } catch (UnsupportedEncodingException e) {
            System.out.println("UTF-8 encoding is not supported.");
            e.printStackTrace();
        }
        return stringToSign;
    }
    /***
     * 计算签名
     * @param stringToSign      签名字符串
     * @param accessKeySecret   阿里云AccessKey Secret加上与号&
     * @return 计算得到的签名
     */
    private String sign(String stringToSign, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM_NAME);
            mac.init(new SecretKeySpec(
                    accessKeySecret.getBytes(ENCODING),
                    ALGORITHM_NAME
            ));
            byte[] signData = mac.doFinal(stringToSign.getBytes(ENCODING));
            String signBase64 = DatatypeConverter.printBase64Binary(signData);
            System.out.println("计算的得到的签名：" + signBase64);
            String signUrlEncode = percentEncode(signBase64);
            System.out.println("UrlEncode编码后的签名：" + signUrlEncode);
            return signUrlEncode;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e.toString());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e.toString());
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }


    private String getResponseWithTimeout(Request q, OkHttpClient client) {
        String ret = null;
        try {
            Response s = client.newCall(q).execute();
            ret = s.body().string();
            s.close();
        } catch (SocketTimeoutException e) {
            ret = null;
            System.err.println("get result timeout");
        } catch (IOException e) {
            System.err.println("get result error " + e.getMessage());
        }

        return ret;
    }

    private String sendPostFile(String url, HashMap<String, String> headers, String fileName, OkHttpClient client) {
        RequestBody body;

        File file = new File(fileName);
        if (!file.isFile()) {
            System.err.println("The filePath is not a file: " + fileName);
            return null;
        } else {
            body = RequestBody.create(MediaType.parse("application/octet-stream"), file);
        }

        Headers.Builder hb = new Headers.Builder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                hb.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(hb.build())
                .post(body)
                .build();

        return getResponseWithTimeout(request, client);
    }

    private String sendPostData(String url, HashMap<String, String> headers, byte[] data, OkHttpClient client) {
        RequestBody body;

        if (data.length == 0) {
            System.err.println("The send data is empty.");
            return null;
        } else {
            body = RequestBody.create(MediaType.parse("application/octet-stream"), data);
        }

        Headers.Builder hb = new Headers.Builder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                hb.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(hb.build())
                .post(body)
                .build();

        return getResponseWithTimeout(request, client);
    }

    private String sendPostLink(String url, HashMap<String, String> headers, String link, OkHttpClient client){
        RequestBody body;

        if (link.isEmpty()) {
            System.err.println("The send link is empty.");
            return null;
        } else {
            url=url+"&audio_address="+link;
        }

        Headers.Builder hb = new Headers.Builder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                hb.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(hb.build())
                .build();

        return getResponseWithTimeout(request, client);
    }
}
