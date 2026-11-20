import { date, type Row } from "./api";
export function nextWork(row: Row) {
  if (row.nextDueAt && row.receiptStatus === "CONFIRMED")
    return `已确认初次通知，${date(row.nextDueAt)}前完成后续提醒`;
  if (row.nextDueAt)
    return `${date(row.nextDueAt)}前${row.receiptStatus === "UNREACHABLE" ? "再次联系" : "核实并记录结果"}`;
  return row.receiptStatus === "UNREACHABLE"
    ? "再次联系并记录结果"
    : "请实际告知并记录联系结果";
}
export const businessTypes = [
  { code: "BIRTHDAY", name: "生日关怀" },
  { code: "VACCINE", name: "疫苗接种" },
  { code: "LECTURE", name: "健康讲座" },
  { code: "OTHER", name: "其他政务" },
];
