package com.anjiplus.template.gaea.business.filter;


import com.alibaba.fastjson.JSONObject;
import com.anji.plus.gaea.bean.ResponseBean;
import com.anji.plus.gaea.cache.CacheHelper;
import com.anji.plus.gaea.constant.GaeaConstant;
import com.anji.plus.gaea.utils.JwtBean;
import com.anjiplus.template.gaea.business.constant.BusinessConstant;
import com.anjiplus.template.gaea.business.modules.accessuser.controller.dto.GaeaUserDto;
import com.anjiplus.template.gaea.business.util.JwtUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.anji.plus.gaea.constant.GaeaConstant.URL_REPLACEMENT;

/**
 * 简单的鉴权
 * @author raodeming
 * @date 2021/6/24.
 */
@Component
@Order(Integer.MIN_VALUE + 99)
public class TokenFilter implements Filter {
    private static final Pattern PATTERN = Pattern.compile(".*().*");
    private static final String USER_GUEST = "guest";
    private static final String SLASH = "/";
    private AntPathMatcher antPathMatcher = new AntPathMatcher();

    @Autowired
    private CacheHelper cacheHelper;
    @Autowired
    private JwtBean jwtBean;

    /** 跳过token验证和权限验证的url清单*/
    @Value("#{'${customer.skip-authenticate-urls}'.split(',')}")
    private List<String> skipAuthenticateUrls;
    private Pattern skipAuthenticatePattern;


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 生成匹配正则，跳过token验证和权限验证的url
        skipAuthenticatePattern = fitByList(skipAuthenticateUrls);
        Filter.super.init(filterConfig);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String uri = request.getRequestURI();

        //OPTIONS直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SLASH.equals(uri)) {
            response.sendRedirect("/index.html");
            return;
        }

        // 不需要token验证和权限验证的url，直接放行
        boolean skipAuthenticate = skipAuthenticatePattern.matcher(uri).matches();
        if (skipAuthenticate) {
            filterChain.doFilter(request, response);
            return;
        }

        //针对大屏分享，优先处理
        String shareToken = request.getHeader("Share-Token");
        if (StringUtils.isNotBlank(shareToken)) {
            //两个接口需要处理
            //  /reportDashboard/getData
            //  /reportDashboard/{reportCode}
            String reportCode = JwtUtil.getReportCode(shareToken);
            if (!uri.endsWith("/getData") && !uri.contains(reportCode)) {
                ResponseBean responseBean = ResponseBean.builder().code("50014")
                        .message("分享链接已过期").build();
                response.getWriter().print(JSONObject.toJSONString(responseBean));
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }


        //获取token
        String token = request.getHeader("Authorization");
        if (StringUtils.isBlank(token)) {
            error(response);
            return;
        }

        // 判断token是否过期
        String loginName = jwtBean.getUsername(token);
        String tokenKey = String.format(BusinessConstant.GAEA_SECURITY_LOGIN_TOKEN, loginName);
        String userKey = String.format(BusinessConstant.GAEA_SECURITY_LOGIN_USER, loginName);
        if (!cacheHelper.exist(tokenKey)) {
            error(response);
            return;
        }
        if (!cacheHelper.exist(userKey)) {
            error(response);
            return;
        }
        String gaeaUserJsonStr = cacheHelper.stringGet(userKey);

        //判断接口权限
        //请求路径
        String requestUrl = request.getRequestURI();
        String methodValue = request.getMethod();
        //请求方法+#+请求路径
        String urlKey = methodValue + GaeaConstant.URL_SPLIT + requestUrl;

        GaeaUserDto gaeaUserDto = JSONObject.parseObject(gaeaUserJsonStr, GaeaUserDto.class);
        List<String> authorities = gaeaUserDto.getAuthorities();
        Map<String, String> applicationNameAllAuthorities = cacheHelper.hashGet(BusinessConstant.GAEA_SECURITY_AUTHORITIES);
        AtomicBoolean authFlag = new AtomicBoolean(false);
        //查询当前请求是否在对应的权限里。即：先精确匹配(保证当前路由是需要精确匹配还是模糊匹配，防止精确匹配的被模糊匹配）
        // 比如：/user/info和/user/**同时存在，/user/info,被/user/**匹配掉
        if (applicationNameAllAuthorities.containsKey(urlKey)) {
            String permissionCode = applicationNameAllAuthorities.get(urlKey);
            if (authorities.contains(permissionCode)) {
                authFlag.set(true);
            }
        } else {
            List<String> collect = applicationNameAllAuthorities.keySet().stream()
                    .filter(key -> StringUtils.isNotBlank(key) && key.contains(URL_REPLACEMENT))
                    .filter(key -> antPathMatcher.match(key, urlKey)).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(collect)) {
                authFlag.set(true);
            }else {
                collect.forEach(key -> {
                    String permissionCode = applicationNameAllAuthorities.getOrDefault(key, "");
                    if (authorities.contains(permissionCode)) {
                        authFlag.set(true);
                    }
                });
            }
        }

        if (!authFlag.get()) {
            //无权限
            authError(response);
            return;
        }




        // 延长有效期
        cacheHelper.stringSetExpire(tokenKey, token, 3600);
        cacheHelper.stringSetExpire(userKey, gaeaUserJsonStr, 3600);

        //执行
        filterChain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }

    /**
     * 根据名单，生成正则
     *
     * @param skipUrlList
     * @return
     */
    private Pattern fitByList(List<String> skipUrlList) {
        if (skipUrlList == null || skipUrlList.size() == 0) {
            return PATTERN;
        }
        StringBuffer patternString = new StringBuffer();
        patternString.append(".*(");

        skipUrlList.stream().forEach(url -> {
            patternString.append(url.trim());
            patternString.append("|");
        });
        if (skipUrlList.size() > 0) {
            patternString.deleteCharAt(patternString.length() - 1);
        }
        patternString.append(").*");

        return Pattern.compile(patternString.toString());
    }

    private void error(HttpServletResponse response) throws IOException {
        ResponseBean responseBean = ResponseBean.builder().code("50014").message("The Token has expired").build();
        response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
        response.getWriter().print(JSONObject.toJSONString(responseBean));
    }

    private void authError(HttpServletResponse response) throws IOException {
        ResponseBean responseBean = ResponseBean.builder().code("User.no.authority").message("没有权限").build();
        response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
        response.getWriter().print(JSONObject.toJSONString(responseBean));
    }
}
