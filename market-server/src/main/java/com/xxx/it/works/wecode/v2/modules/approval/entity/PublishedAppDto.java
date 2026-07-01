package com.xxx.it.works.wecode.v2.modules.approval.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 已上架应用查询结果 DTO
 *
 * <p>对应 selectPublishedList SQL：app_t + version_t 子查询 JOIN</p>
 */
@Data
public class PublishedAppDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应用主键 ID（app_t.id） */
    private Long appPkId;

    /** 应用 ID（app_t.app_id） */
    private String appId;

    /** 应用中文名 */
    private String appNameCn;

    /** 应用英文名 */
    private String appNameEn;

    /** 应用创建时间 */
    private Date createTime;

    /** 应用最后更新时间 */
    private Date lastUpdateTime;

    /** 版本主键 ID（version_t.id） */
    private Long versionId;

    /** 版本号（version_t.version_code） */
    private String versionCode;
}
