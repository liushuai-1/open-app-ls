package com.xxx.it.works.wecode.v2.modules.approval.service.impl;

import com.xxx.it.works.wecode.v2.common.model.ApiResponse;
import com.xxx.it.works.wecode.v2.modules.approval.dto.ApprovalListRequest;
import com.xxx.it.works.wecode.v2.modules.approval.dto.ApprovalProcessRequest;
import com.xxx.it.works.wecode.v2.modules.approval.engine.ApprovalEngine;
import com.xxx.it.works.wecode.v2.modules.approval.entity.AbilityEntity;
import com.xxx.it.works.wecode.v2.modules.approval.entity.AppEntity;
import com.xxx.it.works.wecode.v2.modules.approval.entity.ApprovalRecord;
import com.xxx.it.works.wecode.v2.modules.approval.entity.AppVersionEntity;
import com.xxx.it.works.wecode.v2.modules.approval.entity.PropertyEntity;
import com.xxx.it.works.wecode.v2.modules.approval.entity.PublishedAppDto;
import com.xxx.it.works.wecode.v2.modules.approval.mapper.AbilityMapper;
import com.xxx.it.works.wecode.v2.modules.approval.mapper.AppMapper;
import com.xxx.it.works.wecode.v2.modules.approval.mapper.ApprovalRecordMapper;
import com.xxx.it.works.wecode.v2.modules.approval.mapper.AppVersionMapper;
import com.xxx.it.works.wecode.v2.modules.approval.service.ApprovalService;
import com.xxx.it.works.wecode.v2.modules.approval.vo.ApprovalListVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalService {

    /** 每页最大条数 */
    private static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private ApprovalRecordMapper recordMapper;

    @Autowired
    private AppVersionMapper appVersionMapper;

    @Autowired
    private AppMapper appMapper;

    @Autowired
    private AbilityMapper abilityMapper;

    @Autowired
    private ApprovalEngine approvalEngine;

    @Override
    public ApiResponse<List<ApprovalListVo>> getPendingList(ApprovalListRequest request) {
        int curPage = clampCurPage(request.getCurPage());
        int pageSize = clampPageSize(request.getPageSize());
        int offset = (curPage - 1) * pageSize;

        List<ApprovalRecord> records = recordMapper.selectPendingList(offset, pageSize);
        long total = recordMapper.countPendingList();

        // --- 批量补查，避免 N+1 ---

        // 1. 收集 versionId，批量查版本
        List<Long> versionIds = records.stream()
                .map(r -> safeParseLong(r.getBusinessId()))
                .filter(id -> id != null)
                .collect(Collectors.toList());
        Map<Long, AppVersionEntity> versionMap = batchQueryVersions(versionIds);

        // 2. 收集 appPkId（来自 version.appId），批量查应用
        List<Long> appPkIds = versionMap.values().stream()
                .map(AppVersionEntity::getAppId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AppEntity> appMap = batchQueryApps(appPkIds);

        // 3. 批量查 eamap_app_code
        Map<Long, String> eamapMap = batchQueryEamapAppCodes(appPkIds);

        // 4. 批量查版本属性表 abilityIds
        Map<Long, String> abilityIdsMap = batchQueryVersionAbilityIds(versionIds);

        // 5. 收集所有 abilityId，批量查能力名称
        List<Long> allAbilityIds = abilityIdsMap.values().stream()
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> parseIds(s).stream())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> abilityNameMap = batchQueryAbilityNames(allAbilityIds);

        // --- 装配 VO（单条失败不影响其他） ---
        List<ApprovalListVo> voList = new ArrayList<>();
        for (ApprovalRecord record : records) {
            try {
                ApprovalListVo vo = new ApprovalListVo();
                vo.setId(String.valueOf(record.getId()));
                vo.setBusinessType(record.getBusinessType());
                vo.setBusinessId(record.getBusinessId());
                vo.setApplicantId(record.getApplicantId());
                vo.setStatus(record.getStatus());
                vo.setCreateTime(record.getCreateTime());

                Long versionId = safeParseLong(record.getBusinessId());
                AppVersionEntity version = versionMap.get(versionId);
                if (version != null) {
                    vo.setVersionNo(version.getVersionCode());

                    AppEntity app = appMap.get(version.getAppId());
                    if (app != null) {
                        vo.setAppNameCn(app.getAppNameCn());
                        vo.setAppNameEn(app.getAppNameEn());
                        vo.setAppId(app.getAppId());
                        vo.setHisAppId(eamapMap.get(app.getId()));
                    }

                    String abilityIdsStr = abilityIdsMap.get(versionId);
                    if (abilityIdsStr != null && !abilityIdsStr.isEmpty()) {
                        List<String> names = parseIds(abilityIdsStr).stream()
                                .map(abilityNameMap::get)
                                .filter(n -> n != null)
                                .collect(Collectors.toList());
                        vo.setCapabilityNames(String.join(", ", names));
                    }
                }

                voList.add(vo);
            } catch (Exception e) {
                log.warn("Skipping pending record id={} due to enrichment error", record.getId(), e);
            }
        }

        return successPage(voList, total, curPage, pageSize);
    }

    @Override
    public ApiResponse<List<ApprovalListVo>> getPublishedList(ApprovalListRequest request) {
        int curPage = clampCurPage(request.getCurPage());
        int pageSize = clampPageSize(request.getPageSize());
        int offset = (curPage - 1) * pageSize;

        List<PublishedAppDto> records = recordMapper.selectPublishedList(offset, pageSize);
        long total = recordMapper.countPublishedList();

        // --- 批量补查，避免 N+1 ---

        // 1. 收集 versionId，批量查版本属性表 abilityIds
        List<Long> versionIds = records.stream()
                .map(PublishedAppDto::getVersionId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        Map<Long, String> abilityIdsMap = batchQueryVersionAbilityIds(versionIds);

        // 2. 批量查申请人
        Map<Long, String> applicantMap = batchQueryApplicants(versionIds);

        // 3. 收集 appPkId，批量查 eamap_app_code
        List<Long> appPkIds = records.stream()
                .map(PublishedAppDto::getAppPkId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> eamapMap = batchQueryEamapAppCodes(appPkIds);

        // 4. 收集所有 abilityId，批量查能力名称
        List<Long> allAbilityIds = abilityIdsMap.values().stream()
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> parseIds(s).stream())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> abilityNameMap = batchQueryAbilityNames(allAbilityIds);

        // --- 装配 VO（单条失败不影响其他） ---
        List<ApprovalListVo> voList = new ArrayList<>();
        for (PublishedAppDto record : records) {
            try {
                ApprovalListVo vo = new ApprovalListVo();

                Long appPkId = record.getAppPkId();
                Long versionId = record.getVersionId();

                vo.setId(appPkId != null ? String.valueOf(appPkId) : null);
                vo.setAppId(record.getAppId());
                vo.setHisAppId(eamapMap.get(appPkId));
                vo.setAppNameCn(record.getAppNameCn());
                vo.setAppNameEn(record.getAppNameEn());
                vo.setVersionNo(record.getVersionCode());
                vo.setCreateTime(record.getCreateTime());

                String abilityIdsStr = abilityIdsMap.get(versionId);
                if (abilityIdsStr != null && !abilityIdsStr.isEmpty()) {
                    List<String> names = parseIds(abilityIdsStr).stream()
                            .map(abilityNameMap::get)
                            .filter(n -> n != null)
                            .collect(Collectors.toList());
                    vo.setCapabilityNames(String.join(", ", names));
                }

                vo.setApplicantId(applicantMap.get(versionId));

                voList.add(vo);
            } catch (Exception e) {
                log.warn("Skipping published record appPkId={} due to enrichment error", record.getAppPkId(), e);
            }
        }

        return successPage(voList, total, curPage, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> processApproval(ApprovalProcessRequest request) {
        try {
            Long recordId = Long.parseLong(request.getId());
            approvalEngine.process(recordId, request.getAction());
            return ApiResponse.success();
        } catch (NumberFormatException e) {
            log.error("Invalid approval record id: {}", request.getId(), e);
            return ApiResponse.error("400", "无效的审批记录ID", "Invalid approval record ID");
        } catch (Exception e) {
            log.error("Failed to process approval, id={}, action={}", request.getId(), request.getAction(), e);
            return ApiResponse.error("500", "审批操作失败：" + e.getMessage(), "Approval processing failed: " + e.getMessage());
        }
    }

    // ===================== 分页参数校验 =====================

    private int clampCurPage(Integer curPage) {
        if (curPage == null || curPage < 1) return 1;
        return curPage;
    }

    private int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // ===================== 批量查询 =====================

    private Map<Long, AppVersionEntity> batchQueryVersions(List<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return appVersionMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(AppVersionEntity::getId, v -> v));
    }

    private Map<Long, AppEntity> batchQueryApps(List<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<Long, AppEntity> map = new HashMap<>();
        for (Long id : ids) {
            AppEntity app = appMapper.selectById(id);
            if (app != null) map.put(id, app);
        }
        return map;
    }

    private Map<Long, String> batchQueryEamapAppCodes(List<Long> appIds) {
        if (appIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (PropertyEntity prop : recordMapper.selectEamapAppCodesByAppIds(appIds)) {
            map.put(prop.getParentId(), prop.getPropertyValue());
        }
        return map;
    }

    private Map<Long, String> batchQueryVersionAbilityIds(List<Long> versionIds) {
        if (versionIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (PropertyEntity prop : recordMapper.selectVersionAbilityIdsBatch(versionIds)) {
            map.put(prop.getParentId(), prop.getPropertyValue());
        }
        return map;
    }

    private Map<Long, String> batchQueryApplicants(List<Long> versionIds) {
        if (versionIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        for (PropertyEntity prop : recordMapper.selectApplicantsByVersionIds(versionIds)) {
            map.put(prop.getParentId(), prop.getPropertyValue());
        }
        return map;
    }

    private Map<Long, String> batchQueryAbilityNames(List<Long> abilityIds) {
        if (abilityIds.isEmpty()) return Collections.emptyMap();
        return abilityMapper.selectByIds(abilityIds).stream()
                .collect(Collectors.toMap(AbilityEntity::getId, AbilityEntity::getAbilityNameCn));
    }

    // ===================== 工具方法 =====================

    private List<Long> parseIds(String idsStr) {
        if (idsStr == null || idsStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(idsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    private Long safeParseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return null; }
    }

    private ApiResponse<List<ApprovalListVo>> successPage(List<ApprovalListVo> voList, long total, int curPage, int pageSize) {
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        ApiResponse.PageResponse page = ApiResponse.PageResponse.builder()
                .curPage(curPage)
                .pageSize(pageSize)
                .total(total)
                .totalPages(totalPages)
                .build();
        return ApiResponse.success(voList, page);
    }
}
