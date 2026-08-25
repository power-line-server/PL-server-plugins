package headlessclient;

import java.util.Map;

/** 无头客户端能力接口（HeadlessClient 实现；解耦 ControlServer 的循环引用） */
public interface ClientApi {
    boolean isConnected();
    mindustry.gen.Player player();
    void chat(String message);
    void move(float x, float y);
    void stopMoving();
    void follow(String name);
    void unfollow();
    /** 移动到目标（"x,y" 坐标 / 玩家名 / "player:名" / "team:名"）；persistent=true 持续跟踪目标当前位置 */
    Map<String, Object> moveTo(String target, boolean persistent);
    /** 设置路径点队列（覆盖当前目标），由移动状态机逐点前进 */
    void setPath(java.util.List<float[]> waypoints);
    /** 旋转：角度(Number) 或目标(玩家名/坐标，朝向其方向)；"off"/"stop" 关闭 */
    Map<String, Object> rotate(Object angleOrTarget);
    void stopRotate();
    /** 感知环境：半径内玩家/单位/建筑列表（名字/类型/队伍/坐标/距离/敌我标记）；radius<=0 用默认 1200 */
    Map<String, Object> perceive(Double radius);
    /** 查询当前位置脚下地形（floor/block/深水/实心等） */
    Map<String, Object> tile();
    /** 搜索最近目标：enemyUnit / enemyBuild / ore / team:队伍名 / unit:单位类型 */
    Map<String, Object> scan(String what);
    /** 寻路到目标（"x,y"/玩家名）：地面单位本地 Astar 绕障并自动开始移动；飞行单位直线 */
    Map<String, Object> pathfind(String target);
    /** 采矿："off" 停止 | "auto" 自动找最近矿脉 | "x,y" 指定坐标（持续采矿状态机） */
    Map<String, Object> mine(String target);
    /** 攻击："off" 停止 | "auto" 最近敌军单位 | "x,y"/玩家名（持续 aim+shooting 状态机） */
    Map<String, Object> attack(String target);
    /** 附身："off"/"clear" 回核心 | "auto" 最近同队 AI 单位 | "unit:类型"（Call.unitControl，受服务器规则限制） */
    Map<String, Object> possess(String target);
    /** 地图标记点位："x,y"/玩家名 + 文本（Call.pingLocation，全服可见） */
    void ping(String target, String message);
    /** 行为脚本热重载（behaviors/*.json，sa reload 语义） */
    Map<String, Object> behReload();
    Map<String, Object> behList();
    Map<String, Object> behStatus();
    void build(int tileX, int tileY, String blockName, int rotation);
    void deconstruct(int tileX, int tileY);
    void menu(int menuId, int option);
    void textInput(int id, String text);
    void setPlayerName(String name);
    void requestConnect(String host, int port);
    void requestDisconnect();
}
