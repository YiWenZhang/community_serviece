export function passwordStrength(value: string) {
  const length = [...value].length;
  const bytes = new TextEncoder().encode(value).length;
  if (length < 6 || bytes > 72 || /[\u0000-\u001f\u007f-\u009f]/.test(value))
    return {
      label: "长度不符合要求",
      percent: 0,
      color: "#c84f4f",
      valid: false,
      bytes,
    };
  const weak =
    /^(123456|12345678|123456789|654321|password|qwerty|111111|000000)$/i.test(
      value,
    ) ||
    /^(.)\1+$/u.test(value) ||
    /^\d+$/.test(value);
  const kinds = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^a-zA-Z0-9]/].filter((r) =>
    r.test(value),
  ).length;
  if (!weak && length >= 10 && kinds >= 3)
    return { label: "强", percent: 100, color: "#348967", valid: true, bytes };
  if (!weak && length >= 8 && kinds >= 2)
    return { label: "中", percent: 65, color: "#b78330", valid: true, bytes };
  return { label: "弱", percent: 30, color: "#c84f4f", valid: true, bytes };
}
