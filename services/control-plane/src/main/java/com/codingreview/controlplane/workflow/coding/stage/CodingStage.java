package com.codingreview.controlplane.workflow.coding.stage;

/** 定义正式 Coding 的五个阶段及稳定外部标识。 */
public enum CodingStage {
    WORKSPACE("workspace", "工作区检查"),
    CODING("coding", "Agent 执行"),
    CHANGE_COLLECTION("change-collection", "变更采集"),
    CHANGE_POLICY("change-policy", "路径门禁"),
    MAVEN("maven", "Maven 验证");

    private final String id;
    private final String label;

    /** 绑定协议标识与用户名称。 */
    CodingStage(String id, String label) { this.id = id; this.label = label; }
    /** 返回不依赖枚举名称的外部标识。 */
    public String id() { return id; }
    /** 返回面向用户的阶段名称。 */
    public String label() { return label; }
}
