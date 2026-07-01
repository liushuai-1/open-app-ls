package com.xxx.it.works.wecode.v2.modules.approval.mapper;

import com.xxx.it.works.wecode.v2.modules.approval.entity.AppVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppVersionMapper {

    /**
     * 根据主键 ID 查询版本
     */
    AppVersionEntity selectById(@Param("id") Long id);

    /**
     * 根据主键 ID 列表批量查询版本
     */
    List<AppVersionEntity> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 更新版本状态
     *
     * @param id     版本主键 ID
     * @param status 新版本状态（引用 AppVersionStatusEnum）
     * @return 影响行数
     */
    int updateStatus(@Param("id") Long id, @Param("status") int status);
}
