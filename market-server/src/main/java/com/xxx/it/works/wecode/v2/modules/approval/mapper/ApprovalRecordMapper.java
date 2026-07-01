package com.xxx.it.works.wecode.v2.modules.approval.mapper;

import com.xxx.it.works.wecode.v2.modules.approval.entity.ApprovalRecord;
import com.xxx.it.works.wecode.v2.modules.approval.entity.PropertyEntity;
import com.xxx.it.works.wecode.v2.modules.approval.entity.PublishedAppDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApprovalRecordMapper {

    ApprovalRecord selectById(@Param("id") Long id);

    List<ApprovalRecord> selectPendingList(@Param("offset") int offset, @Param("pageSize") int pageSize);

    long countPendingList();

    List<PublishedAppDto> selectPublishedList(@Param("offset") int offset, @Param("pageSize") int pageSize);

    long countPublishedList();

    int update(ApprovalRecord record);

    String selectApplicantByVersionId(@Param("versionId") Long versionId);

    String selectThirdPartyAppId(@Param("appPkId") Long appPkId);

    /**
     * 从版本属性表查询 abilityIds（逗号分隔的能力主键 ID）
     */
    String selectVersionAbilityIds(@Param("versionId") Long versionId);

    /**
     * 批量查询版本属性表 abilityIds
     */
    List<PropertyEntity> selectVersionAbilityIdsBatch(@Param("versionIds") List<Long> versionIds);

    /**
     * 批量查询每个 versionId 最新的 applicant_id（通过 max(id) 子查询）
     */
    List<PropertyEntity> selectApplicantsByVersionIds(@Param("versionIds") List<Long> versionIds);

    /**
     * 批量查询应用属性表 eamap_app_code
     */
    List<PropertyEntity> selectEamapAppCodesByAppIds(@Param("appIds") List<Long> appIds);
}
