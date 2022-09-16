package io.dataease.plugins.server;

import io.dataease.auth.entity.SysUserEntity;
import io.dataease.auth.entity.TokenInfo;
import io.dataease.auth.service.AuthUserService;
import io.dataease.auth.util.JWTUtils;
import io.dataease.commons.constants.SysLogConstants;
import io.dataease.commons.exception.DEException;
import io.dataease.commons.utils.DeLogUtils;
import io.dataease.commons.utils.LogUtil;
import io.dataease.commons.utils.ServletUtils;
import io.dataease.plugins.config.SpringContextUtil;
import io.dataease.plugins.xpack.dingtalk.dto.response.DingQrResult;
import io.dataease.plugins.xpack.dingtalk.dto.response.DingUserEntity;
import io.dataease.plugins.xpack.dingtalk.dto.response.DingtalkInfo;
import io.dataease.plugins.xpack.dingtalk.service.DingtalkXpackService;
import io.dataease.plugins.xpack.display.dto.response.SysSettingDto;

import io.dataease.service.sys.SysUserService;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import springfox.documentation.annotations.ApiIgnore;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

@ApiIgnore
@RequestMapping("/plugin/dingtalk")
@Controller
public class XDingtalkServer {

    @Resource
    private AuthUserService authUserService;
    @Resource
    private SysUserService sysUserService;

    @ResponseBody
    @GetMapping("/info")
    public DingtalkInfo getDingtalkInfo() {
        DingtalkXpackService dingtalkXpackService = SpringContextUtil.getBean(DingtalkXpackService.class);
        return dingtalkXpackService.info();
    }
    @ResponseBody
    @RequiresPermissions("sysparam:read")
    @PostMapping("/save")
    public void save(@RequestBody List<SysSettingDto> settings) {
        DingtalkXpackService dingtalkXpackService = SpringContextUtil.getBean(DingtalkXpackService.class);
        dingtalkXpackService.save(settings);
    }
    @ResponseBody
    @PostMapping("/testConn")
    public void testConn(@RequestBody DingtalkInfo dingtalkInfo) {
        DingtalkXpackService dingtalkXpackService = SpringContextUtil.getBean(DingtalkXpackService.class);
        try {
            dingtalkXpackService.testConn(dingtalkInfo);
        }catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
    @ResponseBody
    @PostMapping("/getQrParam")
    public DingQrResult getQrParam() {
        DingtalkXpackService dingtalkXpackService = SpringContextUtil.getBean(DingtalkXpackService.class);
        return dingtalkXpackService.getQrParam();
    }

    @GetMapping("/callBack")
    public ModelAndView callBack(@RequestParam("code") String code, @RequestParam("state") String state) {
        ModelAndView modelAndView = new ModelAndView("redirect:/");
        HttpServletResponse response = ServletUtils.response();
        DingtalkXpackService dingtalkXpackService = null;
        try {
            Map<String, DingtalkXpackService> beansOfType = SpringContextUtil.getApplicationContext().getBeansOfType((DingtalkXpackService.class));
            if (beansOfType.keySet().size() == 0) {
                DEException.throwException("缺少钉钉插件");
            }
            dingtalkXpackService = SpringContextUtil.getBean(DingtalkXpackService.class);
            Boolean isOpen = dingtalkXpackService.isOpen();
            if (!isOpen) {
                DEException.throwException("未开启钉钉");
            }
            DingUserEntity dingUserEntity = dingtalkXpackService.userInfo(code);
            String username = dingUserEntity.getUserid();
            String unionid = dingUserEntity.getUnionid();
            SysUserEntity sysUserEntity = authUserService.getUserBySub(unionid, 5);
            if (null == sysUserEntity) {
                String email = StringUtils.isNotBlank(dingUserEntity.getOrg_email()) ? dingUserEntity.getOrg_email() : StringUtils.isNotBlank(dingUserEntity.getEmail()) ? dingUserEntity.getEmail() : "demo@dingtalk.work";
                sysUserService.validateExistUser(username, dingUserEntity.getName(), email);
                sysUserService.saveDingtalkCUser(dingUserEntity, email);
                sysUserEntity = authUserService.getUserBySub(unionid, 5);
            }
            TokenInfo tokenInfo = TokenInfo.builder().userId(sysUserEntity.getUserId()).username(sysUserEntity.getUsername()).build();
            String realPwd = sysUserEntity.getPassword();
            String token = JWTUtils.sign(tokenInfo, realPwd);
            ServletUtils.setToken(token);

            DeLogUtils.save(SysLogConstants.OPERATE_TYPE.LOGIN, SysLogConstants.SOURCE_TYPE.USER, sysUserEntity.getUserId(), null, null, null);

            Cookie cookie_token = new Cookie("Authorization", token);
            cookie_token.setPath("/");

            response.addCookie(cookie_token);
        } catch (Exception e) {

            String msg = e.getMessage();
            if (null != e.getCause()) {
                msg = e.getCause().getMessage();
            }
            try {
                msg = URLEncoder.encode(msg, "UTF-8");
                LogUtil.error(e);
                Cookie cookie_error = new Cookie("DingtalkError", msg);
                cookie_error.setPath("/");
                response.addCookie(cookie_error);
                return modelAndView;
            } catch (UnsupportedEncodingException e1) {
                e.printStackTrace();
            }
        }
        return modelAndView;
    }
}