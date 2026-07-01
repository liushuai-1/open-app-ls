package com.xxx.it.works.wecode.v2.modules.approval.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * KV 属性表查询结果 DTO
 *
 * <p>用于从 openplatform_app_p_t / openplatform_app_version_p_t 等属性表
 * 批量查询 parent_id + property_value 对</p>
 */
@Data
public class PropertyEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属记录主键 ID（parent_id） */
    private Long parentId;

    /** 属性值（property_value） */
    private String propertyValue;
}
