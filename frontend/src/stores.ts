import { defineStore } from "pinia";
import { ref } from "vue";
import { api, csrf, type Row, ApiError } from "./api";

export const useAuth = defineStore("auth", () => {
  const user = ref<Row | null>(null);
  const dictionaries = ref<Row>({
    grids: [],
    serviceNeeds: [],
    vaccineCampaigns: [],
    vaccineProducts: [],
  });
  let restored = false;
  const can = (permission: string) =>
    !!user.value?.permissions?.includes(permission);
  async function restore() {
    if (restored) return;
    try {
      user.value = await api("/auth/me");
      await csrf();
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error;
    } finally {
      restored = true;
    }
  }
  async function login(username: string, password: string) {
    await csrf();
    user.value = await api("/auth/login", "POST", { username, password });
    await csrf();
    restored = true;
  }
  async function logout() {
    await api("/auth/logout", "POST", {});
    user.value = null;
  }
  async function loadDictionaries() {
    dictionaries.value = await api("/dictionaries");
  }
  return { user, can, restore, login, logout, dictionaries, loadDictionaries };
});
