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

package com.tencent.bk.codecc.defect.api;

import com.tencent.bk.codecc.defect.vo.BatchDefectProcessReqVO;
import com.tencent.bk.codecc.defect.vo.FileDefectGatherVO;
import com.tencent.bk.codecc.defect.vo.GetFileContentSegmentReqVO;
import com.tencent.bk.codecc.defect.vo.SingleCommentVO;
import com.tencent.bk.codecc.defect.vo.admin.DeptTaskDefectReqVO;
import com.tencent.bk.codecc.defect.vo.admin.DeptTaskDefectRspVO;
import com.tencent.bk.codecc.defect.vo.common.*;
import com.tencent.devops.common.api.pojo.CodeCCResult;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.data.domain.Sort;

import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import java.util.List;

import static com.tencent.devops.common.api.auth.CodeCCHeaderKt.CODECC_AUTH_HEADER_DEVOPS_TASK_ID;
import static com.tencent.devops.common.api.auth.CodeCCHeaderKt.CODECC_AUTH_HEADER_DEVOPS_USER_ID;

/**
 * 告警查询服务
 */
@Api(tags = {"USER_WARN"}, description = "告警查询服务接口")
@Path("/user/warn")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface UserDefectRestResource
{
    @ApiOperation("初始化告警管理页面的缺陷类型、作者以及树")
    @Path("/checker/authors/toolName/{toolName}")
    @GET
    CodeCCResult<QueryWarningPageInitRspVO> queryCheckersAndAuthors(
            @ApiParam(value = "任务ID", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_TASK_ID)
                    Long taskId,
            @ApiParam(value = "工具名称", required = true)
            @PathParam(value = "toolName")
                    String toolName
    );


    @ApiOperation("查询告警清单")
    @Path("/list")
    @POST
    CodeCCResult<CommonDefectQueryRspVO> queryDefectList(
            @ApiParam(value = "任务ID", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_TASK_ID)
                    long taskId,
            @ApiParam(value = "查询参数详情", required = true)
            @Valid
                    DefectQueryReqVO defectQueryReqVO,
            @ApiParam(value = "页数")
            @QueryParam(value = "pageNum")
                    int pageNum,
            @ApiParam(value = "页面大小")
            @QueryParam(value = "pageSize")
                    int pageSize,
            @ApiParam(value = "排序字段")
            @QueryParam(value = "sortField")
                    String sortField,
            @ApiParam(value = "排序方式")
            @QueryParam(value = "sortType")
                    Sort.Direction sortType);


    @ApiOperation("查询告警详情")
    @Path("/detail")
    @POST
    CodeCCResult<CommonDefectDetailQueryRspVO> queryDefectDetail(
            @ApiParam(value = "任务ID", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_TASK_ID)
                    long taskId,
            @ApiParam(value = "查询参数详情", required = true)
            @Valid
                    CommonDefectDetailQueryReqVO commonDefectDetailQueryReqVO,
            @ApiParam(value = "排序字段")
            @QueryParam(value = "sortField")
                    String sortField,
            @ApiParam(value = "排序方式")
            @QueryParam(value = "sortType")
                    Sort.Direction sortType);

    @ApiOperation("获取文件片段")
    @Path("/fileContentSegment")
    @POST
    CodeCCResult<CommonDefectDetailQueryRspVO> getFileContentSegment(
            @ApiParam(value = "任务ID", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_TASK_ID)
                    long taskId,
            @ApiParam(value = "获取文件片段", required = true)
            @Valid
                    GetFileContentSegmentReqVO getFileContentSegmentReqVO);

    @ApiOperation("告警批量处理")
    @Path("/batch")
    @POST
    CodeCCResult<Boolean> batchDefectProcess(
            @ApiParam(value = "任务ID", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_TASK_ID)
                    long taskId,
            @ApiParam(value = "用户名", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_USER_ID)
                    String userName,
            @ApiParam(value = "批量告警处理请求信息", required = true)
            @Valid
                    BatchDefectProcessReqVO batchDefectProcessReqVO
    );

    @ApiOperation("查询构建列表")
    @Path("/tasks/{taskId}/buildInfos")
    @GET
    CodeCCResult<List<BuildVO>> queryBuildInfos(
            @ApiParam(value = "任务ID", required = true)
            @PathParam(value = "taskId")
                    Long taskId
    );

    @ApiOperation("运营数据:按条件获取任务告警统计信息")
    @Path("/deptTaskDefect")
    @POST
    CodeCCResult<DeptTaskDefectRspVO> queryDeptTaskDefect(
            @ApiParam(value = "用户名", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_USER_ID)
                    String userName,
            @ApiParam(value = "按组织架构查询任务告警请求", required = true)
            @Valid
                    DeptTaskDefectReqVO deptTaskDefectReqVO
    );
    @ApiOperation("添加代码评论")
    @Path("/codeComment/toolName/{toolName}")
    @POST
    CodeCCResult<Boolean> addCodeComment(
            @ApiParam(value = "文件主键id", required = true)
            @QueryParam(value = "fileId")
            String fileId,
            @ApiParam(value = "告警主键id", required = true)
            @QueryParam(value = "defectId")
            String defectId,
            @ApiParam(value = "工具名", required = true)
            @PathParam(value = "toolName")
            String toolName,
            @ApiParam(value = "评论主键id", required = true)
            @QueryParam(value = "commentId")
            String commentId,
            @ApiParam(value = "用户名", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_USER_ID)
            String userName,
            @ApiParam(value = "评论信息", required = true)
            SingleCommentVO singleCommentVO);


    @ApiOperation("更新代码评论")
    @Path("/codeComment/commentId/{commentId}/toolName/{toolName}")
    @PUT
    CodeCCResult<Boolean> updateCodeComment(
            @ApiParam(value = "评论主键id", required = true)
            @PathParam(value = "commentId")
            String commentId,
            @ApiParam(value = "用户名", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_USER_ID)
            String userName,
            @ApiParam(value = "工具名", required = true)
            @PathParam(value = "toolName")
            String toolName,
            @ApiParam(value = "评论信息", required = true)
            SingleCommentVO singleCommentVO);


    @ApiOperation("删除代码评论")
    @Path("/codeComment/commentId/{commentId}/singleCommentId/{singleCommentId}/toolName/{toolName}")
    @DELETE
    CodeCCResult<Boolean> deleteCodeComment(
            @ApiParam(value = "评论主键id", required = true)
            @PathParam(value = "commentId")
            String commentId,
            @ApiParam(value = "单独评论主键id", required = true)
            @PathParam(value = "singleCommentId")
            String singleCommentId,
            @ApiParam(value = "工具名", required = true)
            @PathParam(value = "toolName")
            String toolName,
            @ApiParam(value = "用户名", required = true)
            @HeaderParam(CODECC_AUTH_HEADER_DEVOPS_USER_ID)
            String userName);


    @ApiOperation("查询文件告警收敛清单")
    @Path("/gather/taskId/{taskId}/toolName/{toolName}")
    @GET
    CodeCCResult<FileDefectGatherVO> queryFileDefectGather(
            @ApiParam(value = "任务ID", required = true)
            @PathParam(value = "taskId")
                    long taskId,
            @ApiParam(value = "工具名", required = true)
            @PathParam(value = "toolName")
                    String toolName);
}
