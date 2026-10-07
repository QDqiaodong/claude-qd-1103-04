package com.milktea.bar.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 配方新建/编辑请求。售价由服务端统一校验精度与范围：
 * null / 0 / 负数 / 超过两位小数 / 超上限都会被稳定拒绝，不做任何静默四舍五入。
 * 编辑时携带 version，过期版本（其他页面已先行保存）会被服务端拒绝。
 */
public class RecipeSaveRequest {

    public String name;
    public String baseTea;
    public BigDecimal price;
    public Map<String, Integer> toppings;

    /** 编辑时前端持有的乐观版本；新建不传 */
    public Integer version;
}
