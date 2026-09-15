package com.guyu.agentteam.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class OperationGrantId implements Serializable {

    private String conversationId;
    private String opType;
}
