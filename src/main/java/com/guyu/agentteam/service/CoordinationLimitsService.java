package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.dto.CoordinationLimitsDto;
import com.guyu.agentteam.entity.AppSetting;
import com.guyu.agentteam.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 协作/自由讨论的时长限制：持久化于 app_settings（key=coordination.limits）。
 * ReAct 迭代不设上限，运行时长完全由这里的两个时间兜底；读取时对越界数据钳制，坏数据回落默认值。
 */
@Service
public class CoordinationLimitsService {

    public static final int DEFAULT_OVERALL_MINUTES = 60;
    public static final int DEFAULT_MEMBER_MINUTES = 5;
    private static final int OVERALL_MAX = 1440;
    private static final int MEMBER_MAX = 120;
    private static final String KEY = "coordination.limits";

    private final AppSettingRepository settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public CoordinationLimitsService(AppSettingRepository settings) {
        this.settings = settings;
    }

    public record Limits(int overallMinutes, int memberMinutes) {
    }

    public Limits load() {
        String json = settings.findById(KEY).map(AppSetting::getSettingValue).orElse(null);
        if (json == null || json.isBlank()) {
            return new Limits(DEFAULT_OVERALL_MINUTES, DEFAULT_MEMBER_MINUTES);
        }
        try {
            Limits raw = mapper.readValue(json, Limits.class);
            return new Limits(
                    clamp(raw.overallMinutes(), DEFAULT_OVERALL_MINUTES, OVERALL_MAX),
                    clamp(raw.memberMinutes(), DEFAULT_MEMBER_MINUTES, MEMBER_MAX));
        } catch (Exception e) {
            return new Limits(DEFAULT_OVERALL_MINUTES, DEFAULT_MEMBER_MINUTES);
        }
    }

    public CoordinationLimitsDto save(int overallMinutes, int memberMinutes) {
        if (overallMinutes < 1 || overallMinutes > OVERALL_MAX) {
            throw new ApiException(400, "整体超时需在 1-" + OVERALL_MAX + " 分钟之间");
        }
        if (memberMinutes < 1 || memberMinutes > MEMBER_MAX) {
            throw new ApiException(400, "单成员超时需在 1-" + MEMBER_MAX + " 分钟之间");
        }
        AppSetting s = settings.findById(KEY).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setSettingKey(KEY);
            return n;
        });
        try {
            s.setSettingValue(mapper.writeValueAsString(new Limits(overallMinutes, memberMinutes)));
        } catch (Exception e) {
            throw new ApiException(500, "保存协作限制失败");
        }
        s.setUpdatedAt(System.currentTimeMillis());
        settings.save(s);
        return new CoordinationLimitsDto(overallMinutes, memberMinutes);
    }

    private int clamp(int v, int def, int max) {
        if (v < 1) return def;
        return Math.min(v, max);
    }
}
