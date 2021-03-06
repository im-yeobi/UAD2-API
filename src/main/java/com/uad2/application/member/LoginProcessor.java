package com.uad2.application.member;

/*
 * @USER Jongyeob Kim
 * @DATE 2019-10-05
 * @DESCRIPTION 로그인 처리기 (자동 로그인, 일반 로그인)
 */

import com.uad2.application.common.enumData.CookieName;
import com.uad2.application.exception.ClientException;
import com.uad2.application.member.dto.MemberDto;
import com.uad2.application.member.entity.Member;
import com.uad2.application.member.service.MemberService;
import com.uad2.application.utils.CookieUtil;
import com.uad2.application.utils.EncryptUtil;
import com.uad2.application.utils.SessionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.*;

@Component
public class LoginProcessor {
    private Logger logger = LoggerFactory.getLogger(LoginProcessor.class);

    private final MemberService memberService;

    @Autowired
    public LoginProcessor(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 일반 로그인 처리 메소드
     */
    public void login(HttpServletRequest request,
                      HttpServletResponse response,
                      MemberDto.LoginRequest loginRequest) {
        HttpSession session = request.getSession();
        boolean isAutoLogin = false;
        Member member = null;

        //쿠키 존재 여부
        if (isEmptyLoginCookie(request.getCookies())) {

            member = Optional.ofNullable(memberService.getMemberById(loginRequest.getId()))
                    .orElseThrow(() -> new ClientException("Member is not exist"));

            if (Objects.nonNull(member.getPwd()) &&
                    !member.getPwd().equals(EncryptUtil.encryptMD5(loginRequest.getPwd()))) {
                throw new ClientException("Pwd is not correct");
            }

            isAutoLogin = loginRequest.getIsAutoLogin();
        } else {
            List<Cookie> cookieList = Arrays.asList(request.getCookies());

            String idInCookie = CookieUtil.getCookie(cookieList, CookieName.ID).getValue();
            member = Optional.ofNullable(memberService.getMemberById(idInCookie))
                    .orElseThrow(() -> new ClientException("Member is not exist"));

            isAutoLogin = Boolean.parseBoolean(CookieUtil.getCookie(cookieList, CookieName.IS_AUTO_LOGIN).getValue());

            boolean isWrongRequest = isAutoLogin && isDifferentLoginStatusBetWeenCookieAndDB(cookieList);
            if (isWrongRequest) {
                removeLoginCookie(response);
                SessionUtil.removeAttribute(session, "member");
                throw new ClientException("Member session is not exist");
            }
        }

        if (isAutoLogin) {
            this.autoLogin(session, response, member);
        } else {
            this.onceLogin(session, response, member);
        }
    }

    public void logout(HttpServletRequest request,
                       HttpServletResponse response,
                       HttpSession session) {

        List<Cookie> cookieList = Arrays.asList(request.getCookies());

        boolean isAutoLogin = Boolean.parseBoolean(CookieUtil.getCookie(cookieList, CookieName.IS_AUTO_LOGIN).getValue());
        if (isAutoLogin) {
            //로그인 쿠키와 db 로그인 일치 체크
            String sessionIdInCookie = Optional.ofNullable(CookieUtil.getCookie(cookieList, CookieName.SESSION_ID))
                    .map(Cookie::getValue)
                    .orElse(null);
            String idInCookie = Optional.ofNullable(CookieUtil.getCookie(cookieList, CookieName.ID))
                    .map(Cookie::getValue)
                    .orElse(null);

            Member member = Optional.ofNullable(memberService.getMemberByIdAndSessionId(idInCookie, sessionIdInCookie))
                    .orElseThrow(() -> new ClientException("Member is not exist"));

            memberService.updateSessionInfo(member, null, null);

        }

        removeLoginCookie(response);

        SessionUtil.removeAttribute(session, "member");
    }

    public void checkAutoLogin(List<Cookie> cookieList) {
        boolean isAutoLogin = Boolean.parseBoolean(
                Optional.ofNullable(CookieUtil.getCookie(cookieList, CookieName.IS_AUTO_LOGIN).getValue())
                        .orElse("false")
        );
        logger.debug("isAutoLogin = {} ", isAutoLogin);
        if (isAutoLogin && this.isDifferentLoginStatusBetWeenCookieAndDB(cookieList)) {
            throw new ClientException("Cookie session is not valid");
        }
    }

    private boolean isDifferentLoginStatusBetWeenCookieAndDB(List<Cookie> cookieList) {
        //쿠키 내 로그인 데이터로 db 내 로그인 데이터 일치 확인
        String idInCookie = Optional.ofNullable(CookieUtil.getCookie(cookieList, CookieName.ID).getValue())
                .orElseThrow(() -> new ClientException("Id in cookie is empty"));
        String sessionIdInCookie = Optional.ofNullable(CookieUtil.getCookie(cookieList, CookieName.SESSION_ID).getValue())
                .orElseThrow(() -> new ClientException("SessionId in cookie is empty"));

        //쿠키 내 로그인 데이터가 db 내 로그인 데이터와 일치하지 않을 경우
        logger.debug("idInCookie = {}, sessionIdInCookie = {}", idInCookie, sessionIdInCookie);
        Member member = memberService.getMemberByIdAndSessionId(idInCookie, sessionIdInCookie);
        logger.debug("member = {} ", member);
        return !(Objects.nonNull(member) && this.isValidSessionLimit(member));
    }

    /**
     * 자동 로그인 처리 메소드
     */
    private void autoLogin(HttpSession session, HttpServletResponse response, Member member) {
        //세션 저장 및 자동 로그인 처리
        String sessionId = session.getId();
        this.setLoginCookie(response, member, sessionId, true);
        this.setLoginInDB(member, sessionId, true);
        SessionUtil.setAttribute(session, "member", member);
    }

    /**
     * 1회성 로그인 처리 메소드
     */
    private void onceLogin(HttpSession session, HttpServletResponse response, Member member) {
        String sessionId = session.getId();
        this.setLoginCookie(response, member, sessionId, false);
        this.setLoginInDB(member, sessionId, false);
        SessionUtil.setAttribute(session, "member", member);
    }

    private void setLoginInDB(Member member, String sessionId, boolean isAutoLogin) {
        if (isAutoLogin) {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.add(Calendar.YEAR, 1);
            memberService.updateSessionInfo(member, sessionId, calendar.getTime());
        } else {
            memberService.updateSessionInfo(member, null, null);
        }
    }

    private void setLoginCookie(HttpServletResponse response, Member member, String sessionId, boolean isAutoLogin) {
        int cookieExpiration = isAutoLogin ? CookieUtil.A_YEAR_EXPIRATION : CookieUtil.ONCE_EXPIRATION;
        response.addCookie(CookieUtil.setCookie(CookieName.ID, member.getId(), cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.NAME, member.getName(), cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.PHONE_NUM, member.getPhoneNumber(), cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_WORKER, Integer.toString(member.getIsWorker()), cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.SESSION_ID, sessionId, cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_ADMIN, Integer.toString(member.getIsAdmin()), cookieExpiration));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_AUTO_LOGIN, String.valueOf(isAutoLogin), cookieExpiration));
    }

    private void removeLoginCookie(HttpServletResponse response) {
        response.addCookie(CookieUtil.setCookie(CookieName.ID, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.NAME, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.PHONE_NUM, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_WORKER, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.SESSION_ID, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_ADMIN, null, 0));
        response.addCookie(CookieUtil.setCookie(CookieName.IS_AUTO_LOGIN, null, 0));
    }

    public boolean isEmptyLoginCookie(Cookie[] cookieArray) {
        return !(cookieArray != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.ID) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.NAME) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.PHONE_NUM) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.IS_WORKER) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.SESSION_ID) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.IS_ADMIN) != null &&
                CookieUtil.getCookie(Arrays.asList(cookieArray), CookieName.IS_AUTO_LOGIN) != null);
    }

    private boolean isValidSessionLimit(Member member) {
        // 저장되어 있는 세션의 만료시점이 현재 시간보다 이전인 경우, 유효하지 않은 세션으로 간주한다.
        return member.getSessionLimit() != null
                && member.getSessionLimit().after(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTime());
    }
}
