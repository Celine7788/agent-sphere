package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentPendingWait;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentPendingWaitMapper extends BaseMapper<AgentPendingWait> {
}