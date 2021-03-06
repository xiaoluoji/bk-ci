/*
 * Tencent is pleased to support the open source community by making BK-CODECC 蓝鲸代码检查平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CODECC 蓝鲸代码检查平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bk.codecc.defect.service.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tencent.bk.codecc.coverity.api.ServiceCovDefectRestResource;
import com.tencent.bk.codecc.defect.component.CommonFilterPathComponent;
import com.tencent.bk.codecc.defect.dao.mongorepository.*;
import com.tencent.bk.codecc.defect.dao.mongotemplate.DefectDao;
import com.tencent.bk.codecc.defect.model.*;
import com.tencent.bk.codecc.defect.service.AbstractQueryWarningBizService;
import com.tencent.bk.codecc.defect.service.TreeService;
import com.tencent.bk.codecc.defect.service.newdefectjudge.NewDefectJudgeService;
import com.tencent.bk.codecc.defect.utils.ConvertUtil;
import com.tencent.bk.codecc.defect.vo.*;
import com.tencent.bk.codecc.defect.vo.DefectDetailVO;
import com.tencent.bk.codecc.defect.vo.admin.DeptTaskDefectReqVO;
import com.tencent.bk.codecc.defect.vo.admin.DeptTaskDefectRspVO;
import com.tencent.bk.codecc.defect.vo.common.*;
import com.tencent.bk.codecc.defect.vo.openapi.*;
import com.tencent.bk.codecc.defect.vo.report.CommonChartAuthorVO;
import com.tencent.bk.codecc.task.api.ServiceTaskRestResource;
import com.tencent.bk.codecc.task.constant.TaskMessageCode;
import com.tencent.bk.codecc.task.vo.*;
import com.tencent.bk.codecc.task.vo.gongfeng.ProjectStatVO;
import com.tencent.devops.common.api.QueryTaskListReqVO;
import com.tencent.devops.common.api.exception.CodeCCException;
import com.tencent.devops.common.api.pojo.Page;
import com.tencent.devops.common.api.pojo.CodeCCResult;
import com.tencent.devops.common.auth.api.external.AuthExPermissionApi;
import com.tencent.devops.common.client.Client;
import com.tencent.devops.common.constant.ComConstants;
import com.tencent.devops.common.constant.CommonMessageCode;
import com.tencent.devops.common.constant.RedisKeyConstants;
import com.tencent.devops.common.service.BizServiceFactory;
import com.tencent.devops.common.util.DateTimeUtils;
import com.tencent.devops.common.util.ListSortUtil;
import com.tencent.devops.common.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coverity告警管理服务实现
 *
 * @version V1.0
 * @date 2019/10/24
 */
@Slf4j
@Service("CommonQueryWarningBizService")
public class CommonQueryWarningBizServiceImpl extends AbstractQueryWarningBizService
{
    @Autowired
    private DefectRepository defectRepository;

    @Autowired
    private CodeFileUrlRepository codeFileUrlRepository;

    @Autowired
    private FirstAnalysisSuccessTimeRepository firstSuccessTimeRepository;

    @Autowired
    private BizServiceFactory<TreeService> treeServiceBizServiceFactory;

    @Autowired
    private CheckerRepository checkerRepository;

    @Autowired
    private IgnoreCheckerRepository ignoreCheckerRepository;

    @Autowired
    private NewDefectJudgeService newDefectJudgeService;

    @Autowired
    private AuthExPermissionApi authExPermissionApi;

    @Autowired
    private CommonFilterPathComponent commonFilterPathComponent;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    protected Client client;

    @Value("${codecc.gateway.host}")
    private String codeccGateWay;

    @Autowired
    private BuildDefectRepository buildDefectRepository;

    @Autowired
    private CommonStatisticRepository commonStatisticRepository;

    @Autowired
    private DefectDao defectDao;

    @Override
    public CommonDefectQueryRspVO processQueryWarningRequest(long taskId, DefectQueryReqVO defectQueryReqVO, int pageNum, int pageSize, String sortField, Sort.Direction sortType)
    {
        log.info("query task[{}] defect list by {}", taskId, defectQueryReqVO);
        DefectQueryRspVO defectQueryRspVO = new DefectQueryRspVO();

        //工蜂特殊处理
        String taskCreateFrom = authExPermissionApi.getTaskCreateFrom(taskId);

        // 根据任务ID和工具名查询所有的告警
        List<DefectEntity> defectList = defectRepository.findByTaskIdAndToolName(taskId, defectQueryReqVO.getToolName());

        // 根据根据前端传入的条件过滤告警，并分类统计
        Set<String> defectPaths = filterDefectByCondition(taskId, defectList, defectQueryReqVO, defectQueryRspVO);

        List<DefectBaseVO> defectVOs = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(defectList))
        {
            List<CodeFileUrlEntity> codeFileUrlList = codeFileUrlRepository.findByTaskId(taskId);
            Map<String, CodeFileUrlEntity> codeFileUrlMap = new HashMap<>();
            if (CollectionUtils.isNotEmpty(codeFileUrlList))
            {
                codeFileUrlList.forEach(codeRepo -> codeFileUrlMap.put(codeRepo.getFile(), codeRepo));
            }

            StatisticEntity statisticEntity = commonStatisticRepository.findFirstByTaskIdAndToolNameOrderByTimeDesc(taskId, defectQueryReqVO.getToolName());
            for (DefectEntity defect : defectList)
            {
                DefectBaseVO defectVO = new DefectBaseVO();
                if(judgeFilter(taskCreateFrom,defect.getFilePathname()))
                {
                    continue;
                }
                BeanUtils.copyProperties(defect, defectVO);
                defectVO.setMark(convertMarkStatus(defectVO.getMark(), defectVO.getMarkTime(), statisticEntity));
                defectVOs.add(defectVO);

                //将路径替换成URL
                replaceFileNameWithURL(defectVO, codeFileUrlMap);
            }
        }

        // 处理文件树
        TreeService treeService = treeServiceBizServiceFactory.createBizService(defectQueryReqVO.getToolName(), ComConstants.BusinessType.TREE_SERVICE.value(), TreeService.class);
        Map<String, String> relatePathMap = treeService.getRelatePathMap(taskId);
        defectPaths = convertDefectPathsToRelatePath(defectPaths, relatePathMap);
        TreeNodeVO treeNode = treeService.getTreeNode(taskId, defectPaths);
        defectQueryRspVO.setFilePathTree(treeNode);

        // 排序和分页
        org.springframework.data.domain.Page<DefectBaseVO> defectVOPage = sortAndPage(pageNum, pageSize, sortField, sortType, defectVOs);

        defectQueryRspVO.setDefectList(defectVOPage);
        return defectQueryRspVO;
    }

    protected Set<String> convertDefectPathsToRelatePath(Set<String> defectPaths, Map<String, String> relatePathMap)
    {
        Set<String> defectPathsSet = new HashSet<>();
        defectPaths.forEach(defectPath ->
        {
            defectPath = trimWinPathPrefix(defectPath);
            String defectRelatePath = relatePathMap.get(defectPath.toLowerCase());
            defectPathsSet.add(StringUtils.isEmpty(defectRelatePath) ? defectPath : defectRelatePath);
        });
        return defectPathsSet;
    }

    @Override
    public CommonDefectDetailQueryRspVO processQueryWarningDetailRequest(long taskId, CommonDefectDetailQueryReqVO queryWarningDetailReq, String sortField, Sort.Direction sortType)
    {
        DefectDetailQueryRspVO defectDetailQueryRspVO = new DefectDetailQueryRspVO();

        //查询告警信息
        DefectEntity defectEntity = defectRepository.findByEntityId(queryWarningDetailReq.getEntityId());
        if (defectEntity == null)
        {
            log.error("can't find defect entity by entityId: {}", queryWarningDetailReq.getEntityId());
            throw new CodeCCException(CommonMessageCode.RECORD_NOT_EXITS, new String[]{queryWarningDetailReq.getEntityId()}, null);
        }

        DefectDetailVO defectDetailVO = getDefectDetailVO(taskId, defectEntity);

        StatisticEntity statisticEntity = commonStatisticRepository.findFirstByTaskIdAndToolNameOrderByTimeDesc(taskId, queryWarningDetailReq.getToolName());
        defectDetailVO.setMark(convertMarkStatus(defectDetailVO.getMark(), defectDetailVO.getMarkTime(), statisticEntity));

        CodeFileUrlEntity codeFileUrlEntity = codeFileUrlRepository.findFirstByTaskIdAndFile(taskId, defectEntity.getFilePathname());
        if (codeFileUrlEntity != null)
        {
            Map<String, CodeFileUrlEntity> codeRepoUrlMap = Maps.newHashMap();
            codeRepoUrlMap.put(codeFileUrlEntity.getFile(), codeFileUrlEntity);
            replaceFileNameWithURL(defectDetailVO, codeRepoUrlMap);
        }

        defectDetailQueryRspVO.setDefectDetailVO(defectDetailVO);
        defectDetailQueryRspVO.setFilePath(defectDetailVO.getFilePathname());
        defectDetailQueryRspVO.setFileName(defectDetailVO.getFileName());

        return defectDetailQueryRspVO;
    }

    @Override
    protected DefectDetailVO getFilesContent(DefectDetailVO defectDetailVO)
    {
        CodeCCResult<DefectDetailVO> codeCCResult = client.get(ServiceCovDefectRestResource.class).getDefectDetail(defectDetailVO);

        if (codeCCResult.isNotOk() || null == codeCCResult.getData())
        {
            log.error("get defect detail fail!");
            throw new CodeCCException(TaskMessageCode.REGISTER_COV_PROJ_FAIL);
        }
        return codeCCResult.getData();
    }

    @Override
    public QueryWarningPageInitRspVO processQueryWarningPageInitRequest(Long taskId, String toolName)
    {
        // Coverity告警管理的页面的下拉框初始化不再通过单独的接口返回，而是通过告警列表查询接口一并返回
        return null;
    }

    @Override
    public CheckerPkgDefectVO getPkgDefectList(String toolName, String pkgId, Integer bgId, Long taskId,
                                               Integer pageNum, Integer pageSize, String sortField, Sort.Direction sortType) {
        CheckerPkgDefectVO checkerPkgDefectVO = new CheckerPkgDefectVO();
        Sort pageSort;
        if (StringUtils.isEmpty(sortField) || null == sortType) {
            pageSort = new Sort(Sort.Direction.ASC, "id");
        } else {
            pageSort = new Sort(sortType, sortField);
        }
        //封装分页类
        Pageable pageable = new PageRequest(null == pageNum ? 0 : pageNum - 1, null == pageSize ? 10 : pageSize, pageSort);
        List<String> checkerNames;
        List<CheckerDetailEntity> checkerDetailEntityList = checkerRepository.findByToolNameAndPkgKind(toolName, pkgId);
        if (CollectionUtils.isEmpty(checkerDetailEntityList)) {
            log.error("no checker info found! tool name: {}, pkg id: {}", toolName, pkgId);
            throw new CodeCCException(CommonMessageCode.PARAMETER_IS_INVALID, new String[]{"pkg id"}, null);
        }
        checkerNames = checkerDetailEntityList.stream().map(CheckerDetailEntity::getCheckerKey).collect(Collectors.toList());
        List<DefectEntity> defectEntityList;
        Page<com.tencent.bk.codecc.defect.vo.openapi.DefectDetailVO> defectDetailVoPage;
        List<com.tencent.bk.codecc.defect.vo.openapi.DefectDetailVO> defectDetailVoList = Lists.newArrayList();
        //情况1，如果传了单个的任务id
        if (null != taskId && taskId != 0L) {
            String taskCreateFrom = authExPermissionApi.getTaskCreateFrom(taskId);
            org.springframework.data.domain.Page<DefectEntity> defectEntityPage = defectRepository.findByCheckerNameInAndTaskIdIn(checkerNames, new ArrayList<Long>() {{
                add(taskId);
            }}, pageable);
            defectEntityList = defectEntityPage.getContent();
            CodeCCResult<TaskDetailVO> taskDetailVOCodeCCResult = client.get(ServiceTaskRestResource.class).getTaskInfoById(taskId);
            TaskDetailVO taskDetailVO;
            if (taskDetailVOCodeCCResult.isOk() && null != taskDetailVOCodeCCResult.getData()) {
                taskDetailVO = taskDetailVOCodeCCResult.getData();
            } else {
                log.error("no task info found! task id: {}", taskId);
                throw new CodeCCException(CommonMessageCode.PARAMETER_IS_INVALID, new String[]{"task id"}, null);
            }
            if (CollectionUtils.isNotEmpty(defectEntityList)) {
                defectDetailVoList = defectEntityList.stream().filter(elem -> !judgeFilter(taskCreateFrom, elem.getFilePathname()))
                        .map(defectEntity -> new com.tencent.bk.codecc.defect.vo.openapi.DefectDetailVO(
                                defectEntity.getSeverity(),
                                defectEntity.getIgnoreReasonType(),
                                defectEntity.getFilePathname(),
                                defectEntity.getAuthorList(),
                                defectEntity.getCreateTime(),
                                defectEntity.getCheckerName(),
                                Math.max(defectEntity.getExcludeTime(),
                                        Math.max(defectEntity.getIgnoreTime(),
                                                Math.max(defectEntity.getCreateTime(), defectEntity.getFixedTime()))),
                                null,
                                String.format("http://%s/code/%s/task/%d/defect/coverity/list", codeccGateWay, taskDetailVO.getNameEn(), taskId),
                                defectEntity.getFileVersion(),
                                defectEntity.getStatus(),
                                Long.valueOf(defectEntity.getId())
                        )
                ).collect(Collectors.toList());
            }
            defectDetailVoPage = new Page<>(defectEntityPage.getTotalElements(), null == pageNum ? 1: pageNum,
                    null == pageSize ? 10 : pageSize, defectEntityPage.getTotalPages(), defectDetailVoList);
        }
        //情况2，如果传了bg_id
        else if (null != bgId && bgId != 0L) {
            QueryTaskListReqVO queryTaskListReqVO = new QueryTaskListReqVO();
            // 经沟通啄木鸟接口只允许查询工蜂开源扫描的任务
            queryTaskListReqVO.setCreateFrom(Lists.newArrayList(ComConstants.BsTaskCreateFrom.GONGFENG_SCAN.value()));
            // 设置排除标识
            queryTaskListReqVO.setIsExcludeTaskIds(Boolean.toString(true));
            queryTaskListReqVO.setBgId(bgId);
            queryTaskListReqVO.setToolName(toolName);
            // 排除空集合
            queryTaskListReqVO.setTaskIds(Sets.newHashSet());
            queryTaskListReqVO.setStatus(ComConstants.Status.ENABLE.value());
            CodeCCResult<TaskListVO> taskListCodeCCResult =
                    client.get(ServiceTaskRestResource.class).getTaskDetailList(queryTaskListReqVO);

            if (taskListCodeCCResult.isNotOk() || null == taskListCodeCCResult.getData()) {
                log.error("query task info by bg id fail! bg id : {}", bgId);
                throw new CodeCCException(CommonMessageCode.PARAMETER_IS_INVALID, new String[]{"bg id"}, null);
            }
            List<TaskDetailVO> taskList = taskListCodeCCResult.getData().getEnableTasks();

            org.springframework.data.domain.Page<DefectEntity> defectEntityPage = defectRepository.findByCheckerNameInAndTaskIdIn(checkerNames, taskList.stream().
                    map(TaskDetailVO::getTaskId).collect(Collectors.toList()), pageable);
            defectEntityList = defectEntityPage.getContent();
            Map<Long, String> taskNameMap = taskList.stream().collect(Collectors.toMap(TaskDetailVO::getTaskId, TaskBaseVO::getNameEn));
            if (CollectionUtils.isNotEmpty(defectEntityList))
            {
                defectDetailVoList = defectEntityList.stream().filter(elem -> !judgeFilter(ComConstants.BsTaskCreateFrom.GONGFENG_SCAN.value(),
                                elem.getFilePathname()))
                        .map(defectEntity -> new com.tencent.bk.codecc.defect.vo.openapi.DefectDetailVO(
                                defectEntity.getSeverity(),
                                defectEntity.getIgnoreReasonType(),
                                defectEntity.getFilePathname(),
                                defectEntity.getAuthorList(),
                                defectEntity.getCreateTime(),
                                defectEntity.getCheckerName(),
                                Math.max(defectEntity.getExcludeTime(),
                                        Math.max(defectEntity.getIgnoreTime(),
                                                Math.max(defectEntity.getCreateTime(), defectEntity.getFixedTime()))),
                                null,
                                String.format("http://%s/code/%s/task/%d/defect/coverity/list", codeccGateWay, taskNameMap.get(defectEntity.getTaskId()),
                                        defectEntity.getTaskId()),
                                defectEntity.getFileVersion(),
                                defectEntity.getStatus(),
                                Long.valueOf(defectEntity.getId())
                        )
                ).collect(Collectors.toList());
            }
            defectDetailVoPage = new Page<>(defectEntityPage.getTotalElements(), null == pageNum ? 1 : pageNum,
                    null == pageSize ? 10 : pageSize, defectEntityPage.getTotalPages(), defectDetailVoList);
        }
        //情况3，如果都没传
        else {
            org.springframework.data.domain.Page<DefectEntity> defectEntityPage = defectRepository.findByCheckerNameIn(checkerNames, pageable);
            defectEntityList = defectEntityPage.getContent();
            CodeCCResult<List<TaskBaseVO>> taskListCodeCCResult = client.get(ServiceTaskRestResource.class).getTaskInfosByIds(defectEntityList.stream().map(DefectEntity::getTaskId).
                    distinct().collect(Collectors.toList()));
            if (taskListCodeCCResult.isNotOk() || null == taskListCodeCCResult.getData()) {
                log.error("query task info by task id list fail!");
                throw new CodeCCException(CommonMessageCode.INTERNAL_SYSTEM_FAIL);
            }
            List<TaskBaseVO> taskList = taskListCodeCCResult.getData();
            Map<Long, String> taskNameMap = taskList.stream().collect(Collectors.toMap(TaskBaseVO::getTaskId, TaskBaseVO::getNameEn));
            if (CollectionUtils.isNotEmpty(defectEntityList)) {
                defectDetailVoList = defectEntityList.stream().map(defectEntity ->
                        new com.tencent.bk.codecc.defect.vo.openapi.DefectDetailVO(
                                defectEntity.getSeverity(),
                                defectEntity.getIgnoreReasonType(),
                                defectEntity.getFilePathname(),
                                defectEntity.getAuthorList(),
                                defectEntity.getCreateTime(),
                                defectEntity.getCheckerName(),
                                Math.max(defectEntity.getExcludeTime(),
                                        Math.max(defectEntity.getIgnoreTime(),
                                                Math.max(defectEntity.getCreateTime(), defectEntity.getFixedTime()))),
                                null,
                                String.format("http://%s/code/%s/task/%d/defect/coverity/list", codeccGateWay, taskNameMap.get(defectEntity.getTaskId()),
                                        defectEntity.getTaskId()),
                                defectEntity.getFileVersion(),
                                defectEntity.getStatus(),
                                Long.valueOf(defectEntity.getId())
                        )
                ).collect(Collectors.toList());
            }
            defectDetailVoPage = new Page<>(defectEntityPage.getTotalElements(), null == pageNum ? 1 : pageNum,
                    null == pageSize ? 10 : pageSize, defectEntityPage.getTotalPages(), defectDetailVoList);
        }


        checkerPkgDefectVO.setTaskId(taskId);
        checkerPkgDefectVO.setToolName(toolName);
        checkerPkgDefectVO.setDefectList(defectDetailVoPage);
        return checkerPkgDefectVO;
    }

    @Override
    public CheckerPkgDefectRespVO processCheckerPkgDefectRequest(String toolName, String pkgId, Integer bgId,
            Integer deptId, Integer pageNum, Integer pageSize, Sort.Direction sortType)
    {
        log.info("query tool[{}] pkgId[{}] in bg[{}]", toolName, pkgId, bgId);

        // 默认条件：任务为启用状态
        int taskStatus = ComConstants.Status.ENABLE.value();

        CheckerPkgDefectRespVO pkgDefectRespVO = new CheckerPkgDefectRespVO();
        List<TaskDefectVO> statisticsTask = Lists.newArrayList();

        // 1.获取工具指定规则包的规则列表，并组装成Map
        List<CheckerDetailEntity> detailList = checkerRepository.findByToolNameAndPkgKind(toolName, pkgId);
        Set<String> checkerNameSet = Sets.newHashSet();
        if (CollectionUtils.isNotEmpty(detailList))
        {
            checkerNameSet = detailList.stream().map(CheckerDetailEntity::getCheckerKey).collect(Collectors.toSet());
        }

        // 分页查询任务列表
        QueryTaskListReqVO queryTaskListReqVO =
                getQueryTaskListReqVO(toolName, bgId, deptId, taskStatus, pageNum, pageSize, sortType);
        queryTaskListReqVO.setSortField("task_id");
        // 经沟通啄木鸟接口只允许查询工蜂开源扫描的任务
        queryTaskListReqVO.setCreateFrom(Lists.newArrayList(ComConstants.BsTaskCreateFrom.GONGFENG_SCAN.value()));

        CodeCCResult<Page<TaskDetailVO>> taskCodeCCResult = client.get(ServiceTaskRestResource.class).getTaskDetailPage(queryTaskListReqVO);
        if (taskCodeCCResult.isNotOk() || taskCodeCCResult.getData() == null)
        {
            log.error("task list is empty! status {}, toolName {}", taskStatus, toolName);
            throw new CodeCCException(CommonMessageCode.INTERNAL_SYSTEM_FAIL);
        }

        Page<TaskDetailVO> resultPage = taskCodeCCResult.getData();
        List<TaskDetailVO> enableTasks = resultPage.getRecords();
        pageNum = resultPage.getPage();
        pageSize = resultPage.getPageSize();
        int totalPages = resultPage.getTotalPages();
        long totalCount = resultPage.getCount();

        Set<Integer> gfProjectIds = Sets.newHashSet();
        List<Long> taskIdList = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(enableTasks))
        {
            enableTasks.forEach(elem ->{
                Integer projectId = elem.getGongfengProjectId();
                if (projectId != null)
                {
                    gfProjectIds.add(projectId);
                }
                taskIdList.add(elem.getTaskId());
            });
        }
        log.info("commCheckerPkgDefectReq tool:{},page taskId:{}, pageNum:{}, pageSize:{}, totalPage:{}, count:{}",
                toolName, taskIdList.size(), pageNum, pageSize, totalPages, totalCount);

        // 5.获取开启规则包的任务ID 指定规则集的告警列表
        List<DefectEntity> defectEntityList =
                defectRepository.findByToolNameAndTaskIdInAndCheckerNameIn(toolName, taskIdList, checkerNameSet);

        // 组装成Map映射
        Map<Long, List<DefectEntity>> defectMap = getLongListDefectMap(defectEntityList);
        // 获取代码语言类型元数据
        List<MetadataVO> metadataVoList = getCodeLangMetadataVoList();
        // 批量获取代码库地址
        Map<Integer, GongfengPublicProjVO> gongfengPublicProjVoMap = getGongfengPublicProjVoMap(gfProjectIds);
        // 获取代码库度量数据
        Map<Integer, ProjectStatVO> gongfengStatProjVoMap = getGongfengStatProjVoMap(bgId, gfProjectIds);

        Map<String, String> deptInfo =
                (Map<String, String>) redisTemplate.opsForHash().entries(RedisKeyConstants.KEY_DEPT_INFOS);

        // 获取代码行数
        Map<Long, Long> codeLineCountMap = getCodeLineNumMap(taskIdList, null);

        for (TaskDetailVO taskDetailVO : enableTasks)
        {
            TaskDefectVO taskDefectVO = new TaskDefectVO();
            BeanUtils.copyProperties(taskDetailVO, taskDefectVO);
            taskDefectVO.setCodeLang(ConvertUtil.convertCodeLang(taskDetailVO.getCodeLang(), metadataVoList));
            taskDefectVO.setBgName(deptInfo.get(String.valueOf(taskDetailVO.getBgId())));
            taskDefectVO.setDeptName(deptInfo.get(String.valueOf(taskDetailVO.getDeptId())));
            taskDefectVO.setCenterName(deptInfo.get(String.valueOf(taskDetailVO.getCenterId())));
            taskDefectVO.setAnalyzeDate("");

            // 设置工蜂代码库信息
            setGongfengInfo(gongfengPublicProjVoMap, gongfengStatProjVoMap, taskDetailVO, taskDefectVO);

            long taskId = taskDetailVO.getTaskId();
            Long codeLineCount = codeLineCountMap.get(taskId);
            taskDefectVO.setCodeLineNum(Integer.valueOf(String.valueOf(codeLineCount == null ? 0 : codeLineCount)));

            List<DefectEntity> defectList = defectMap.get(taskId);
            if (CollectionUtils.isEmpty(defectList))
            {
                statisticsTask.add(taskDefectVO);
                continue;
            }

            for (DefectEntity defect : defectList)
            {
                // 过滤不是啄木鸟规则的告警
                String checkerName = defect.getCheckerName().toUpperCase();
                if (!checkerNameSet.contains(checkerName))
                {
                    continue;
                }

                int status = defect.getStatus();
                int severity = defect.getSeverity();
                if (ComConstants.DefectStatus.NEW.value() == status)
                {
                    taskDefectVO.getExistCount().count(severity);
                }
                else if ((ComConstants.DefectStatus.FIXED.value() & status) > 0)
                {
                    taskDefectVO.getFixedCount().count(severity);
                }
                else if ((ComConstants.DefectStatus.IGNORE.value() & status) > 0)
                {
                    taskDefectVO.getIgnoreCount().count(defect.getIgnoreReasonType());
                }
                else if ((ComConstants.DefectStatus.CHECKER_MASK.value() & status) > 0 ||
                        (ComConstants.DefectStatus.PATH_MASK.value() & status) > 0)
                {
                    taskDefectVO.getExcludedCount().count(severity);
                }
            }

            statisticsTask.add(taskDefectVO);
        }
        // 数据列表分页
        Page<TaskDefectVO> taskDetailVoPage = new Page<>(totalCount, pageNum, pageSize, totalPages, statisticsTask);

        pkgDefectRespVO.setCheckerCount(checkerNameSet.size());
        pkgDefectRespVO.setTaskCount(statisticsTask.size());
        pkgDefectRespVO.setStatisticsTask(taskDetailVoPage);
        return pkgDefectRespVO;
    }

    /**
     * 根据根据前端传入的条件过滤告警，并分类统计
     *
     * @param taskId
     * @param defectList
     * @param defectQueryReqVO
     * @param queryRspVO
     * @return
     */
    @Override
    public Set<String> filterDefectByCondition(long taskId, List<?> defectList, DefectQueryReqVO defectQueryReqVO, CommonDefectQueryRspVO queryRspVO)
    {
        if (CollectionUtils.isEmpty(defectList))
        {
            log.info("task[{}] defect entity list is empty", taskId);
            return new HashSet<>();
        }

        // 按构建号筛选
        boolean needBuildIdFilter = false;
        Set<String> buildDefectIds = Sets.newHashSet();
        if (StringUtils.isNotEmpty(defectQueryReqVO.getBuildId()))
        {
            if (defectSynsSuccess(taskId, defectQueryReqVO.getToolName(), defectQueryReqVO.getBuildId()))
            {
                List<BuildDefectEntity> buildFiles = buildDefectRepository.findByTaskIdAndToolNameAndBuildId(taskId, defectQueryReqVO.getToolName(),
                        defectQueryReqVO.getBuildId());
                if (CollectionUtils.isNotEmpty(buildFiles))
                {
                    for (BuildDefectEntity buildDefectEntity : buildFiles)
                    {
                        buildDefectIds.add(buildDefectEntity.getDefectId());
                    }
                }
            }
            needBuildIdFilter = true;
        }

        // 查询新老告警判定时间
        long newDefectJudgeTime = newDefectJudgeService.getNewDefectJudgeTime(taskId, defectQueryReqVO.getToolName(), null);

        //根据查询条件进行过滤，并统计数量
        String condChecker = defectQueryReqVO.getChecker();
        String condAuthor = defectQueryReqVO.getAuthor();
        Set<String> condFileList = getConditionFilterFiles(defectQueryReqVO);
        Set<String> condSeverityList = defectQueryReqVO.getSeverity();
        Set<String> condStatusList = defectQueryReqVO.getStatus();
        if (CollectionUtils.isEmpty(condStatusList))
        {
            condStatusList = new HashSet<>(3);
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.NEW.value()));
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.FIXED.value()));
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.IGNORE.value()));
        }
        String condStartCreateTime = defectQueryReqVO.getStartCreateTime();
        String condEndCreateTime = defectQueryReqVO.getEndCreateTime();
        String condStartFixTime = defectQueryReqVO.getStartFixTime();
        String condEndFixTime = defectQueryReqVO.getEndFixTime();
        Set<String> condDefectTypeList = defectQueryReqVO.getDefectType();

        int seriousCount = 0;
        int normalCount = 0;
        int promptCount = 0;
        int existCount = 0;
        int fixCount = 0;
        int ignoreCount = 0;
        int totalCount = 0;
        int newCount = 0;
        int historyCount = 0;
        Map<String, Integer> checkerMap = new TreeMap<>();
        Map<String, Integer> authorMap = new TreeMap<>();
        Set<String> defectPaths = new HashSet<>();
        Iterator<DefectEntity> it = (Iterator<DefectEntity>) defectList.iterator();
        while (it.hasNext())
        {
            DefectEntity defectEntity = it.next();

            // 判断是否匹配告警状态，先收集统计匹配告警状态的规则、处理人、文件路径
            boolean notMatchStatus = isNotMatchStatus(condStatusList, defectEntity.getStatus());
            String checkerName = defectEntity.getCheckerName();
            Set<String> authorList = defectEntity.getAuthorList();
            if (!notMatchStatus)
            {
                checkerMap.put(checkerName, checkerMap.get(checkerName) == null ? 1 : checkerMap.get(checkerName) + 1);

                if (CollectionUtils.isNotEmpty(authorList))
                {
                    authorList.forEach(author ->
                    {
                        authorMap.put(author, authorMap.get(author) == null ?1 : authorMap.get(author) + 1);
                    });
                }
                defectPaths.add(defectEntity.getFilePathname());
            }

            // 按构建号筛选
            if (needBuildIdFilter && !buildDefectIds.contains(defectEntity.getId()))
            {
                it.remove();
                continue;
            }

            // 规则类型条件不为空且与当前数据的规则类型不匹配时，判断为true移除，否则false不移除
            boolean notMatchChecker = StringUtils.isNotEmpty(condChecker) && !condChecker.equals(checkerName);
            if (notMatchChecker)
            {
                it.remove();
                continue;
            }

            //告警作者条件不为空且与当前数据的作者不匹配时，判断为true移除，否则false不移除
            boolean notMatchAuthor = StringUtils.isNotEmpty(condAuthor) && (CollectionUtils.isEmpty(authorList) || !authorList.contains(condAuthor));
            if (notMatchAuthor)
            {
                it.remove();
                continue;
            }

            // 根据文件过滤
            boolean notMatchFilePath = false;
            if (CollectionUtils.isNotEmpty(condFileList))
            {
                // 判断文件名是否匹配文件路径列表，不匹配就移除
                notMatchFilePath = !checkIfMaskByPath(defectEntity.getFilePathname(), condFileList);
            }
            if (notMatchFilePath)
            {
                it.remove();
                continue;
            }

            // 根据创建时间过滤，判断为true移除，否则false不移除
            boolean notMatchCreateTime = DateTimeUtils.filterDate(condStartCreateTime, condEndCreateTime, defectEntity.getCreateTime());
            if (notMatchCreateTime)
            {
                it.remove();
                continue;
            }

            // 根据修复时间过滤，判断为true移除，否则false不移除
            boolean notMatchFixTime = DateTimeUtils.filterDate(condStartFixTime, condEndFixTime, defectEntity.getFixedTime());
            if (notMatchFixTime)
            {
                it.remove();
                continue;
            }

            // 根据状态过滤，注：已修复和其他状态（忽略，路径屏蔽，规则屏蔽）不共存，已忽略状态优先于屏蔽
            int status = defectEntity.getStatus();
            if (ComConstants.DefectStatus.NEW.value() == status)
            {
                existCount++;
            }
            else if ((ComConstants.DefectStatus.FIXED.value() & status) > 0)
            {
                fixCount++;
            }
            else if ((ComConstants.DefectStatus.IGNORE.value() & status) > 0)
            {
                ignoreCount++;
            }
            // 严重程度条件不为空且与当前数据的严重程度不匹配时，判断为true移除，否则false不移除
            if (notMatchStatus)
            {
                it.remove();
                continue;
            }

            // 根据严重等级过滤
            int severity = defectEntity.getSeverity();
            if (ComConstants.SERIOUS == severity)
            {
                seriousCount++;
            }
            else if (ComConstants.NORMAL == severity)
            {
                normalCount++;
            }
            else if (ComConstants.PROMPT == severity)
            {
                promptCount++;
            }
            // 严重程度条件不为空且与当前数据的严重程度不匹配时，判断为true移除，否则false不移除
            boolean notMatchSeverity = CollectionUtils.isNotEmpty(condSeverityList) && !condSeverityList.contains(String.valueOf(severity));
            if (notMatchSeverity)
            {
                it.remove();
                continue;
            }

            // 根据新旧告警类型过滤
            if (defectEntity.getCreateTime() > newDefectJudgeTime)
            {
                newCount++;
            }
            else
            {
                historyCount++;
            }
            String defectType = defectEntity.getCreateTime() > newDefectJudgeTime ? ComConstants.DefectType.NEW.stringValue() : ComConstants.DefectType.HISTORY.stringValue();
            boolean notMatchDefectType = CollectionUtils.isNotEmpty(condDefectTypeList) && !condDefectTypeList.contains(defectType);
            if (notMatchDefectType)
            {
                it.remove();
                continue;
            }
            totalCount++;
        }

        DefectQueryRspVO defectQueryRspVO = (DefectQueryRspVO) queryRspVO;
        defectQueryRspVO.setSeriousCount(seriousCount);
        defectQueryRspVO.setNormalCount(normalCount);
        defectQueryRspVO.setPromptCount(promptCount);
        defectQueryRspVO.setExistCount(existCount);
        defectQueryRspVO.setFixCount(fixCount);
        defectQueryRspVO.setIgnoreCount(ignoreCount);
        defectQueryRspVO.setNewCount(newCount);
        defectQueryRspVO.setHistoryCount(historyCount);
        defectQueryRspVO.setTotalCount(totalCount);
        defectQueryRspVO.setCheckerMap(checkerMap);
        defectQueryRspVO.setAuthorMap(authorMap);
        defectQueryRspVO.setNewDefectJudgeTime(newDefectJudgeTime);
        return defectPaths;
    }

    @Override
    public ToolDefectRspVO processToolWarningRequest(Long taskId, DefectQueryReqVO queryWarningReq, Integer pageNum,
            Integer pageSize, String sortField, Sort.Direction sortType)
    {
        log.info("query task[{}] defect list by {}", taskId, queryWarningReq);
        String toolName = queryWarningReq.getToolName();
        ToolDefectRspVO toolDefectRspVO = new ToolDefectRspVO();

        // 根据任务ID和工具名查询所有的告警
        List<DefectEntity> defectList = defectRepository.findByTaskIdAndToolName(taskId, toolName);

        // 根据根据前端传入的条件过滤告警，并分类统计
        filterDefectByCondition(taskId, defectList, queryWarningReq, toolDefectRspVO);

        List<DefectDetailExtVO> defectDetailExtVOs = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(defectList))
        {
            // 获取任务信息
            CodeCCResult<TaskDetailVO> taskInfoCodeCCResult = client.get(ServiceTaskRestResource.class).getTaskInfoById(taskId);
            TaskDetailVO taskDetailVO = taskInfoCodeCCResult.getData();
            if (taskInfoCodeCCResult.isNotOk() || taskDetailVO == null)
            {
                log.error("get task info fail! task id is: {}, msg: {}", taskId, taskInfoCodeCCResult.getMessage());
                throw new CodeCCException(CommonMessageCode.INTERNAL_SYSTEM_FAIL);
            }
            String projectId = taskDetailVO.getProjectId();

            defectList.forEach(defect ->
            {
                DefectDetailExtVO defectVO = new DefectDetailExtVO();
                BeanUtils.copyProperties(defect, defectVO, "filePathname");
                if (ComConstants.Tool.COVERITY.name().equals(toolName))
                {
                    defectVO.setCid(Long.valueOf(defect.getId()));
                }
                defectVO.setFilePathName(defect.getFilePathname());
                String url =
                        String.format("http://%s/codecc/%s/task/%d/defect/compile/%s/list?entityId=%s", codeccGateWay,
                                projectId, taskId, toolName, defect.getEntityId()); defectVO.setDefectDetailUrl(url);
                defectDetailExtVOs.add(defectVO);
            });
        }

        // 默认以创建时间排序和分页
        Page<DefectDetailExtVO> detailExtPage = sortAndPage(pageNum, pageSize, sortField, sortType, defectDetailExtVOs);

        toolDefectRspVO.setTaskId(taskId);
        toolDefectRspVO.setToolName(toolName);
        toolDefectRspVO.setDefectList(detailExtPage);

        return toolDefectRspVO;
    }


    @Override
    public DeptTaskDefectRspVO processDeptTaskDefectReq(DeptTaskDefectReqVO deptTaskDefectReqVO)
    {
        log.info("DeptTaskDefectReqVO content: {}", deptTaskDefectReqVO);
        String toolName = deptTaskDefectReqVO.getToolName();
        long startTime = DateTimeUtils.getTimeStampStart(deptTaskDefectReqVO.getStartDate());
        long endTime = DateTimeUtils.getTimeStampEnd(deptTaskDefectReqVO.getEndDate());

        DeptTaskDefectRspVO taskDefectRspVO = new DeptTaskDefectRspVO();
        List<TaskDefectVO> taskDefectList = Lists.newArrayList();

        List<TaskDetailVO> taskInfoVoList = getTaskDetailVoList(deptTaskDefectReqVO);

        if (CollectionUtils.isNotEmpty(taskInfoVoList))
        {
            // 按部门和中心排序
            taskInfoVoList.sort((Comparator.comparingInt(TaskDetailVO::getDeptId)
                    .thenComparingInt(TaskDetailVO::getCenterId)));

            // 获取代码语言类型元数据
            List<MetadataVO> metadataVoList = getCodeLangMetadataVoList();
            // 获取组织架构信息
            Map<String, String> deptInfo =
                    (Map<String, String>) redisTemplate.opsForHash().entries(RedisKeyConstants.KEY_DEPT_INFOS);

            Set<Long> taskIdSet = taskInfoVoList.stream().map(TaskDetailVO::getTaskId).collect(Collectors.toSet());

            // 批量获取最近分析日志
            Map<Long, TaskLogVO> taskLogVoMap = getTaskLogVoMap(taskIdSet, toolName);

            // 获取所有状态的告警,组装成Map映射
            List<DefectEntity> defectEntityList = defectDao.batchQueryDefect(toolName, taskIdSet, null, null);
            Map<Long, List<DefectEntity>> defectMap = getLongListDefectMap(defectEntityList);

            for (TaskDetailVO taskDetailVO : taskInfoVoList)
            {
                TaskDefectVO taskDefectVO = new TaskDefectVO();
                BeanUtils.copyProperties(taskDetailVO, taskDefectVO);
                taskDefectVO.setCodeLang(ConvertUtil.convertCodeLang(taskDetailVO.getCodeLang(), metadataVoList));
                taskDefectVO.setBgName(deptInfo.get(String.valueOf(taskDetailVO.getBgId())));
                taskDefectVO.setDeptName(deptInfo.get(String.valueOf(taskDetailVO.getDeptId())));
                taskDefectVO.setCenterName(deptInfo.get(String.valueOf(taskDetailVO.getCenterId())));
                String projectId = taskDefectVO.getProjectId();
                long taskId = taskDetailVO.getTaskId();
                taskDefectVO.setRepoUrl(
                        String.format("http://%s/codecc/%s/task/%s/detail", codeccGateWay,projectId, taskId));
                setAnalyzeDateStatus(taskId, taskLogVoMap, taskDefectVO);

                CommonChartAuthorVO newAddCount = new CommonChartAuthorVO();
                taskDefectVO.setNewAddCount(newAddCount);
                taskDefectVO.setCreatedDate(taskDetailVO.getCreatedDate());

                List<DefectEntity> defectList = defectMap.get(taskId);
                if (CollectionUtils.isEmpty(defectList))
                {
                    taskDefectList.add(taskDefectVO);
                    continue;
                }

                int defectTimeoutNum = 0;
                for (DefectEntity defect : defectList)
                {
                    long createTime = defect.getCreateTime();
                    int status = defect.getStatus();
                    int severity = defect.getSeverity();
                    if (createTime > startTime && createTime <= endTime)
                    {
                        // 不是屏蔽忽略的告警（有效新增）
                        if ((ComConstants.DefectStatus.CHECKER_MASK.value() & status) == 0 &&
                                (ComConstants.DefectStatus.PATH_MASK.value() & status) == 0 &&
                                (ComConstants.DefectStatus.IGNORE.value() & status) == 0)
                        {
                            taskDefectVO.getNewAddCount().count(severity);
                        }
                    }
                    // 已修复告警
                    long fixedTime = defect.getFixedTime();
                    if (fixedTime > startTime && fixedTime <= endTime)
                    {
                        if ((ComConstants.DefectStatus.FIXED.value() & status) > 0)
                        {
                            taskDefectVO.getFixedCount().count(severity);
                        }
                    }
                    // 遗留未修复的告警
                    if (createTime <= endTime && ComConstants.DefectStatus.NEW.value() == status)
                    {
                        taskDefectVO.getExistCount().count(severity);
                        // 统计超时告警数
                        if ((endTime - createTime) / DateTimeUtils.DAY_TIMESTAMP > deptTaskDefectReqVO.getTimeoutDays())
                        {
                            defectTimeoutNum++;
                        }
                    }
                }
                taskDefectVO.setTimeoutDefectNum(defectTimeoutNum);
                taskDefectList.add(taskDefectVO);
            }
        }

        taskDefectRspVO.setTaskDefectVoList(taskDefectList);

        return taskDefectRspVO;
    }

    @Override
    public ToolDefectRspVO processDeptDefectList(DeptTaskDefectReqVO defectQueryReq, Integer pageNum, Integer pageSize,
            String sortField, Sort.Direction sortType)
    {
        log.info("processDeptDefectList req content: {}", defectQueryReq);

        String toolName = defectQueryReq.getToolName();
        long endTime = DateTimeUtils.getTimeStamp(defectQueryReq.getEndDate() + " 23:59:59");
        boolean severityFlag = defectQueryReq.getSeverity() != null;

        ToolDefectRspVO defectRspVO = new ToolDefectRspVO();

        List<TaskDetailVO> taskDetailVoList = getTaskDetailVoList(defectQueryReq);
        if (CollectionUtils.isNotEmpty(taskDetailVoList))
        {
            // 取出任务ID集合
            Set<Long> taskIdSet = taskDetailVoList.stream().map(TaskDetailVO::getTaskId).collect(Collectors.toSet());

            // 获取遗留状态的告警
            List<DefectEntity> defectEntityList =
                    defectDao.batchQueryDefect(toolName, taskIdSet, null, ComConstants.DefectStatus.NEW.value());

            List<DefectDetailExtVO> defectDetailExtVOs = Lists.newArrayList();
            if (CollectionUtils.isNotEmpty(defectEntityList))
            {
                for (DefectEntity defect : defectEntityList)
                {
                    // 是否过滤严重级别
                    if (severityFlag)
                    {
                        if (defect.getSeverity() != defectQueryReq.getSeverity())
                        {
                            continue;
                        }
                    }
                    // 遗留未修复的告警
                    long createTime = defect.getCreateTime();
                    if (createTime <= endTime && ComConstants.DefectStatus.NEW.value() == defect.getStatus())
                    {
                        DefectDetailExtVO defectVO = new DefectDetailExtVO();
                        BeanUtils.copyProperties(defect, defectVO, "filePathname");
                        defectVO.setFilePathName(defect.getFilePathname());
                        defectDetailExtVOs.add(defectVO);
                    }
                }
                Page<DefectDetailExtVO> defectDetailExtVoPage =
                        sortAndPage(pageNum, pageSize, sortField, sortType, defectDetailExtVOs);

                defectRspVO.setDefectList(defectDetailExtVoPage);

                /* 有告警的任务信息 */
                List<DefectDetailExtVO> records = defectDetailExtVoPage.getRecords();
                Map<Long, TaskDetailVO> taskDetailVoMap = Maps.newHashMap();
                if (CollectionUtils.isNotEmpty(records))
                {
                    Set<Long> defectTaskIdSet =
                            records.stream().map(DefectDetailExtVO::getTaskId).collect(Collectors.toSet());
                    taskDetailVoMap =
                            taskDetailVoList.stream().filter(task -> defectTaskIdSet.contains(task.getTaskId()))
                                    .collect(Collectors
                                            .toMap(TaskDetailVO::getTaskId, Function.identity(), (k, v) -> v));
                }
                defectRspVO.setTaskDetailVoMap(taskDetailVoMap);
            }
        }
        return defectRspVO;
    }

    @NotNull
    private Map<Long, List<DefectEntity>> getLongListDefectMap(List<DefectEntity> defectEntityList)
    {
        Map<Long, List<DefectEntity>> defectMap = Maps.newHashMap();
        if (CollectionUtils.isNotEmpty(defectEntityList))
        {
            for (DefectEntity defectEntity : defectEntityList)
            {
                if (judgeFilter(ComConstants.BsTaskCreateFrom.GONGFENG_SCAN.value(), defectEntity.getFilePathname()))
                {
                    continue;
                }
                long taskId = defectEntity.getTaskId();

                List<DefectEntity> defectList = defectMap.computeIfAbsent(taskId, val -> Lists.newArrayList());
                defectList.add(defectEntity);
            }
        }
        return defectMap;
    }

    private void filterDefectByCondition(long taskId, List<DefectEntity> defectList, DefectQueryReqVO defectQueryReqVO,
            ToolDefectRspVO defectQueryRspVO)
    {
        if (CollectionUtils.isEmpty(defectList))
        {
            log.info("task[{}] defect entity list is empty", taskId);
        }

        FirstAnalysisSuccessEntity firstSuccessTimeEntity =
                firstSuccessTimeRepository.findFirstByTaskIdAndToolName(taskId, defectQueryReqVO.getToolName());
        long firstSuccessTime = 0L;
        if (firstSuccessTimeEntity != null)
        {
            firstSuccessTime = firstSuccessTimeEntity.getFirstAnalysisSuccessTime();
        }
        //根据查询条件进行过滤，并统计数量
        String condAuthor = defectQueryReqVO.getAuthor();
        String condChecker = defectQueryReqVO.getChecker();
        String condStartCreateTime = defectQueryReqVO.getStartCreateTime();
        String condEndCreateTime = defectQueryReqVO.getEndCreateTime();
        Set<String> condFileList = getConditionFilterFiles(defectQueryReqVO);
        Set<String> condSeverityList = defectQueryReqVO.getSeverity();
        Set<String> condDefectTypeList = defectQueryReqVO.getDefectType();
        Set<String> condStatusList = defectQueryReqVO.getStatus();
        if (CollectionUtils.isEmpty(condStatusList))
        {
            condStatusList = new HashSet<>(3);
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.NEW.value()));
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.FIXED.value()));
            condStatusList.add(String.valueOf(ComConstants.DefectStatus.IGNORE.value()));
        }
        Iterator<DefectEntity> it = defectList.iterator();
        while (it.hasNext())
        {
            DefectEntity defectEntity = it.next();

            // 判断是否匹配告警状态，先收集统计匹配告警状态的规则、处理人、文件路径
            boolean notMatchStatus = isNotMatchStatus(condStatusList, defectEntity.getStatus());
            String checkerName = defectEntity.getCheckerName();
            Set<String> authorList = defectEntity.getAuthorList();

            // 规则类型条件不为空且与当前数据的规则类型不匹配时，判断为true移除，否则false不移除
            if (StringUtils.isNotEmpty(condChecker) && !condChecker.equals(checkerName))
            {
                it.remove();
                continue;
            }
            //告警作者条件不为空且与当前数据的作者不匹配时，判断为true移除，否则false不移除
            boolean notMatchAuthor = StringUtils.isNotEmpty(condAuthor) &&
                    (CollectionUtils.isEmpty(authorList) || !authorList.contains(condAuthor));
            if (notMatchAuthor)
            {
                it.remove();
                continue;
            }
            // 根据创建时间过滤，判断为true移除，否则false不移除
            boolean notMatchDateTime =
                    DateTimeUtils.filterDate(condStartCreateTime, condEndCreateTime, defectEntity.getCreateTime());
            if (notMatchDateTime)
            {
                it.remove();
                continue;
            }
            // 根据文件过滤
            boolean notMatchFilePath = false;
            if (CollectionUtils.isNotEmpty(condFileList))
            {
                // 判断文件名是否匹配文件路径列表，不匹配就移除
                notMatchFilePath = !checkIfMaskByPath(defectEntity.getFilePathname(), condFileList);
            }
            if (notMatchFilePath)
            {
                it.remove();
                continue;
            }

            // 严重程度条件不为空且与当前数据的严重程度不匹配时，判断为true移除，否则false不移除
            if (notMatchStatus)
            {
                it.remove();
                continue;
            }
            // 根据严重等级过滤
            int severity = defectEntity.getSeverity();

            // 严重程度条件不为空且与当前数据的严重程度不匹配时，判断为true移除，否则false不移除
            boolean notMatchSeverity = CollectionUtils.isNotEmpty(condSeverityList) &&
                    !condSeverityList.contains(String.valueOf(severity));
            if (notMatchSeverity)
            {
                it.remove();
                continue;
            }
            // 根据新旧告警类型过滤
            String defectType =
                    defectEntity.getCreateTime() > firstSuccessTime ? ComConstants.DefectType.NEW.stringValue() :
                            ComConstants.DefectType.HISTORY.stringValue();
            boolean notMatchDefectType =
                    CollectionUtils.isNotEmpty(condDefectTypeList) && !condDefectTypeList.contains(defectType);
            if (notMatchDefectType)
            {
                it.remove();
            }
        }
    }

    @NotNull
    private <T>Page<T> sortAndPage(Integer pageNum, Integer pageSize, String sortField,
            Sort.Direction sortType, List<T> defectBaseVoList)
    {
        if (StringUtils.isEmpty(sortField))
        {
            sortField = "createTime";
        }
        if (null == sortType)
        {
            sortType = Sort.Direction.ASC;
        }

        ListSortUtil.sort(defectBaseVoList, sortField, sortType.name());
        int total = defectBaseVoList.size();
        pageNum = pageNum == null || pageNum - 1 < 0 ? 0 : pageNum - 1;

        int pageSizeNum = 10;
        if (pageSize != null && pageSize >= 0)
        {
            pageSizeNum = pageSize;
        }

        int totalPageNum = 0;
        if (total > 0)
        {
            totalPageNum = (total + pageSizeNum - 1) / pageSizeNum;
        }
        int subListBeginIdx = pageNum * pageSizeNum;
        int subListEndIdx = subListBeginIdx + pageSizeNum;
        if (subListBeginIdx > total)
        {
            subListBeginIdx = 0;
        }
        defectBaseVoList = defectBaseVoList.subList(subListBeginIdx, subListEndIdx > total ? total : subListEndIdx);

        return new Page<>(total, pageNum + 1, pageSizeNum, totalPageNum, defectBaseVoList);
    }


    protected Set<String> getConditionFilterFiles(DefectQueryReqVO defectQueryReqVO)
    {
        return defectQueryReqVO.getFileList();
    }

    protected boolean checkIfMaskByPath(String filePathname, Set<String> condFileList)
    {
        return PathUtils.checkIfMaskByPath(filePathname, condFileList);
    }

    /**
     * 用文件具体的代码仓库URL代替文件名展示
     *
     * @param defectBaseVO
     * @param codeRepoUrlMap
     */
    private void replaceFileNameWithURL(DefectBaseVO defectBaseVO, Map<String, CodeFileUrlEntity> codeRepoUrlMap)
    {
        String filePathname = trimWinPathPrefix(defectBaseVO.getFilePathname());
        int fileNameIndex = filePathname.lastIndexOf("/");
        if (fileNameIndex == -1)
        {
            fileNameIndex = filePathname.lastIndexOf("\\");
        }
        String fileName = filePathname.substring(fileNameIndex + 1);
        defectBaseVO.setFileName(fileName);
        CodeFileUrlEntity codeRepoUrlEntity = codeRepoUrlMap.get(filePathname);
        if (codeRepoUrlEntity != null)
        {
            defectBaseVO.setFilePathname(codeRepoUrlEntity.getUrl());
            defectBaseVO.setFileVersion(codeRepoUrlEntity.getVersion());
        }
    }

    protected String trimWinPathPrefix(String filePath)
    {
        return filePath;
    }

    /**
     * 查询所有的告警ID
     *
     * @param taskId
     * @return
     */
    public Set<Long> queryIds(Long taskId, String toolName)
    {
        List<DefectEntity> defectList = defectRepository.findIdsByTaskIdAndToolName(taskId, toolName);

        Set<Long> cidSet = new HashSet<>();
        if (CollectionUtils.isNotEmpty(defectList))
        {
            defectList.forEach(defect -> cidSet.add(Long.valueOf(defect.getId())));
        }
        log.info("task [{}] cidSet size: {}", taskId, cidSet.size());
        return cidSet;
    }


    @Override
    public int getSubmitStepNum()
    {
        return ComConstants.Step4Cov.DEFECT_SYNS.value();
    }
}
