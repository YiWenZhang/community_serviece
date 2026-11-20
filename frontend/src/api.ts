import axios from "axios";
import { ElMessage } from "element-plus";

// API中的BIGINT按字符串保留；所有会话及业务状态只保存在内存中。
export type Row = Record<string, any>;
export const http = axios.create({
  baseURL: "/api/v1",
  withCredentials: true,
  timeout: 30000,
});
let csrfToken = "";
let selectedCommunity = "";
export function setCommunity(id: string) {
  selectedCommunity = id === "0" ? "" : id;
}
export async function csrf() {
  const response = await http.get("/auth/csrf");
  csrfToken = response.data.data.token;
}
http.interceptors.request.use((config) => {
  if (selectedCommunity)
    config.headers.set("X-Community-Id", selectedCommunity);
  if (!["get", "head", "options"].includes(config.method || "get"))
    config.headers.set("X-XSRF-TOKEN", csrfToken);
  return config;
});
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status || 0;
    if (status === 401) window.dispatchEvent(new Event("session-expired"));
    const data = error.response?.data;
    return Promise.reject(
      new ApiError(
        data?.message ||
          (status === 0
            ? "无法连接后端，请确认后端已启动在 8080 端口。"
            : "请求未成功，请稍后重试。"),
        status,
      ),
    );
  },
);
export async function api(
  path: string,
  method = "GET",
  data?: unknown,
  key?: string,
): Promise<any> {
  const result = await http.request({
    url: path,
    method,
    data,
    headers: key ? { "Idempotency-Key": key } : {},
  });
  return result.data.data;
}
export function query(values: Row) {
  const result = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (
      value !== "" &&
      value !== null &&
      value !== undefined &&
      value !== false
    )
      result.set(key, String(value));
  });
  return result.toString();
}
export function fail(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "操作失败，请重试");
}
export function idempotency() {
  return crypto.randomUUID();
}
export function date(value?: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
export async function download(id: string, name: string) {
  const response = await http.get(`/files/${id}/content`, {
    responseType: "blob",
  });
  const url = URL.createObjectURL(response.data);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const labels: Row = {
  ACTIVE: "启用",
  DISABLED: "停用",
  INACTIVE: "停用",
  ARCHIVED: "已归档",
  DRAFT: "草稿",
  PENDING: "待处理",
  PROCESSING: "处理中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  FAILED: "失败",
  OPEN: "待服务",
  FOLLOWING: "跟进中",
  CLOSED: "已闭环",
  CONFIRMED: "已确认接收",
  UNREACHABLE: "未联系上",
  REFUSED: "拒绝接收",
  MALE: "男",
  FEMALE: "女",
  UNKNOWN: "待核实",
  VACCINE: "疫苗接种",
  BIRTHDAY: "生日关怀",
  LECTURE: "健康讲座",
  OTHER: "其他服务",
  HOME_VISIT: "上门告知",
  FAMILY_RELAY: "家属转告",
  BULLETIN: "公告栏张贴",
  POSTED: "已张贴",
  RECONTACT: "再次联系",
  VERIFY_APPOINTMENT: "核实预约",
  PRE_VACCINE_REMINDER: "接种前提醒",
  ELIGIBLE: "符合资格",
  INELIGIBLE: "不符合资格",
  NOT_VACCINATED: "未接种",
  PARTIAL: "部分接种",
  ADMIN: "管理员",
  GRID_WORKER: "网格员",
  STATISTICIAN: "统计员",
  SATISFIED: "满意",
  NEUTRAL: "一般",
  DISSATISFIED: "不满意",
};
export const label = (value?: string) => (value ? labels[value] || value : "—");
