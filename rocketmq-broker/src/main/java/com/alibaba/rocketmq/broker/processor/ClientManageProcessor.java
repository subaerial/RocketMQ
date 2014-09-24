/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.broker.processor;

import com.alibaba.rocketmq.broker.BrokerController;
import com.alibaba.rocketmq.broker.client.ClientChannelInfo;
import com.alibaba.rocketmq.broker.client.ConsumerGroupInfo;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.constant.PermName;
import com.alibaba.rocketmq.common.protocol.RequestCode;
import com.alibaba.rocketmq.common.protocol.ResponseCode;
import com.alibaba.rocketmq.common.protocol.header.*;
import com.alibaba.rocketmq.common.protocol.heartbeat.ConsumerData;
import com.alibaba.rocketmq.common.protocol.heartbeat.HeartbeatData;
import com.alibaba.rocketmq.common.protocol.heartbeat.ProducerData;
import com.alibaba.rocketmq.common.protocol.heartbeat.SubscriptionData;
import com.alibaba.rocketmq.common.subscription.SubscriptionGroupConfig;
import com.alibaba.rocketmq.common.sysflag.TopicSysFlag;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.exception.RemotingCommandException;
import com.alibaba.rocketmq.remoting.netty.NettyRequestProcessor;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


/**
 * Client注册与注销管理
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-26
 */
public class ClientManageProcessor implements NettyRequestProcessor {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.BrokerLoggerName);

    private final BrokerController brokerController;


    public ClientManageProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }


    @Override
    public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
            throws RemotingCommandException {
        switch (request.getCode()) {
        case RequestCode.HEART_BEAT:
            return this.heartBeat(ctx, request);
        case RequestCode.UNREGISTER_CLIENT:
            return this.unregisterClient(ctx, request);
        case RequestCode.GET_CONSUMER_LIST_BY_GROUP:
            return this.getConsumerListByGroup(ctx, request);
        default:
            break;
        }
        return null;
    }


    public RemotingCommand getConsumerListByGroup(ChannelHandlerContext ctx, RemotingCommand request)
            throws RemotingCommandException {
        final RemotingCommand response =
                RemotingCommand.createResponseCommand(GetConsumerListByGroupResponseHeader.class);
        final GetConsumerListByGroupRequestHeader requestHeader =
                (GetConsumerListByGroupRequestHeader) request
                    .decodeCommandCustomHeader(GetConsumerListByGroupRequestHeader.class);

        ConsumerGroupInfo consumerGroupInfo =
                this.brokerController.getConsumerManager().getConsumerGroupInfo(
                    requestHeader.getConsumerGroup());
        if (consumerGroupInfo != null) {
            List<String> clientIds = consumerGroupInfo.getAllClientId();
            if (!clientIds.isEmpty()) {
                GetConsumerListByGroupResponseBody body = new GetConsumerListByGroupResponseBody();
                body.setConsumerIdList(clientIds);
                response.setBody(body.encode());
                response.setCode(ResponseCode.SUCCESS);
                response.setRemark(null);
                return response;
            }
            else {
                log.warn("getAllClientId failed, {} {}", requestHeader.getConsumerGroup(),
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            }
        }
        else {
            log.warn("getConsumerGroupInfo failed, {} {}", requestHeader.getConsumerGroup(),
                RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        }

        response.setCode(ResponseCode.SYSTEM_ERROR);
        response.setRemark("no consumer for this group, " + requestHeader.getConsumerGroup());
        return response;
    }


    public RemotingCommand unregisterClient(ChannelHandlerContext ctx, RemotingCommand request)
            throws RemotingCommandException {
        final RemotingCommand response =
                RemotingCommand.createResponseCommand(UnregisterClientResponseHeader.class);
        final UnregisterClientRequestHeader requestHeader =
                (UnregisterClientRequestHeader) request
                    .decodeCommandCustomHeader(UnregisterClientRequestHeader.class);

        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(//
            ctx.channel(),//
            requestHeader.getClientID(),//
            request.getLanguage(),//
            request.getVersion()//
                );

        // 注销Producer
        {
            final String group = requestHeader.getProducerGroup();
            if (group != null) {
                this.brokerController.getProducerManager().unregisterProducer(group, clientChannelInfo);
            }
        }

        // 注销Consumer
        {
            final String group = requestHeader.getConsumerGroup();
            if (group != null) {
                this.brokerController.getConsumerManager().unregisterConsumer(group, clientChannelInfo);
            }
        }

        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }


    public RemotingCommand heartBeat(ChannelHandlerContext ctx, RemotingCommand request) {
        RemotingCommand response = RemotingCommand.createResponseCommand(null);

        HeartbeatData heartbeatData = HeartbeatData.decode(request.getBody(), HeartbeatData.class);

        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(//
            ctx.channel(),//
            heartbeatData.getClientID(),//
            request.getLanguage(),//
            request.getVersion()//
                );

        // 注册Consumer
        for (ConsumerData data : heartbeatData.getConsumerDataSet()) {
            SubscriptionGroupConfig subscriptionGroupConfig =
                    this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(
                        data.getGroupName());
            if (null != subscriptionGroupConfig) {
                // 如果是单元化模式，则对 topic 进行设置
                int topicSysFlag = 0;
                if (data.isUnitMode()) {
                    topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
                }
                String newTopic = MixAll.getRetryTopic(data.getGroupName());
                this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(//
                    newTopic,//
                    subscriptionGroupConfig.getRetryQueueNums(), //
                    PermName.PERM_WRITE | PermName.PERM_READ, topicSysFlag);
            }

            boolean changed = this.brokerController.getConsumerManager().registerConsumer(//
                data.getGroupName(),//
                clientChannelInfo,//
                data.getConsumeType(),//
                data.getMessageModel(),//
                data.getConsumeFromWhere(),//
                data.getSubscriptionDataSet()//
                );

            if (changed) {
                log.info("registerConsumer info changed {} {}",//
                    data.toString(),//
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel())//
                );

                for (SubscriptionData subscriptionData : data.getSubscriptionDataSet()) {
                    this.brokerController.getTopicConfigManager().updateTopicUnitSubFlag(
                        subscriptionData.getTopic(), data.isUnitMode());
                }
            }
        }

        // 注册Producer
        for (ProducerData data : heartbeatData.getProducerDataSet()) {
            this.brokerController.getProducerManager().registerProducer(data.getGroupName(),
                clientChannelInfo);
        }

        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }
}
