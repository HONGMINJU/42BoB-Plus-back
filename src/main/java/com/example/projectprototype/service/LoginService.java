package com.example.projectprototype.service;

import com.example.projectprototype.dto.SessionDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@Service
@PropertySource("classpath:application.properties")
public class LoginService {
    @Value("${42OAuthUid}")
    String uid;

    @Value("${42OAuthSecret}")
    String secret;

    @Value("${baseUrl}")
    String redirectUri;

    public String getOAuthToken(String code) {
        redirectUri += "42OAuth";
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> request = new HttpEntity<String>(headers);
        ResponseEntity<String> response = null;
        String accessTokenUrl = "https://api.intra.42.fr/oauth/token"
                + "?grant_type=authorization_code"
                + "&client_id=" + uid
                + "&client_secret=" +secret
                + "&code=" + code
                + "&redirect_uri=" + redirectUri;
        response = restTemplate.exchange(accessTokenUrl, HttpMethod.POST, request, String.class);

        //parse를 쓰지 않고, dto로 넘길수도 있다는 모양. (추후 받아올 데이터가 늘어나면 변경예정)
        JSONParser parser = new JSONParser();
        String access_token = null;
        try{
            Object obj = parser.parse(response.getBody());
            JSONObject jsonObj = (JSONObject) obj;
            access_token = (String)jsonObj.get("access_token");
        }catch (Exception e)
        {
            e.printStackTrace();
        }
        return access_token;
    }

    public SessionDto getUserInfo(String token) {

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer "+ token);
        headers.add("Content-Type", "application/json; charset=utf-8");
        HttpEntity<String> request = new HttpEntity<String>(headers);
        ResponseEntity<String> response = null;
        String getInfoUrl = "https://api.intra.42.fr/v2/me";
        response = restTemplate.exchange(getInfoUrl, HttpMethod.GET, request, String.class);
        SessionDto sessionDto = new SessionDto();
        JSONParser parser = new JSONParser();
        try{
            Object obj = parser.parse(response.getBody());
            JSONObject jsonObj = (JSONObject) obj;
            sessionDto.setUserId((String)jsonObj.get("login"));
            sessionDto.setEmail((String)jsonObj.get("email"));
        }catch (Exception e) {
            e.printStackTrace();
        }
        return sessionDto;
    }

    public HttpSession getSession(HttpServletRequest req, SessionDto sessionDto) {

        HttpSession session = req.getSession();

        if ((SessionDto)session.getAttribute("session") == null)
            session.setAttribute("session", sessionDto);
        return session;
    }

    public void destorySession(HttpSession httpSession) {
        httpSession.invalidate();
    }
}

