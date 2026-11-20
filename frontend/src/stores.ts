import { defineStore } from "pinia";
import { ref } from "vue";
import { api, csrf, setCommunity, type Row, ApiError } from "./api";

export const useAuth = defineStore("auth", () => {
  const user = ref<Row | null>(null);
  const communities = ref<Row[]>([]);
  const communityId = ref("");
  const switching = ref(false);
  function remember(value: Row) {
    user.value = value;
    communities.value = value.communities || [];
    communityId.value = String(value.communityId);
    setCommunity(communityId.value);
  }
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
      remember(await api("/auth/me"));
      await csrf();
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error;
    } finally {
      restored = true;
    }
  }
  async function login(username: string, password: string) {
    await csrf();
    setCommunity("");
    remember(await api("/auth/login", "POST", { username, password }));
    await csrf();
    restored = true;
  }
  async function logout() {
    await api("/auth/logout", "POST", {});
    user.value = null;
    communities.value = [];
    communityId.value = "";
    setCommunity("");
  }
  async function loadDictionaries() {
    dictionaries.value = await api("/dictionaries");
  }
  async function switchCommunity(id: string) {
    if (switching.value) return;
    switching.value = true;
    const old = communityId.value;
    setCommunity(id);
    try {
      remember(await api("/auth/me"));
      await loadDictionaries();
    } catch (e) {
      setCommunity(old);
      throw e;
    } finally {
      switching.value = false;
    }
  }
  async function refreshCommunities() {
    remember(await api("/auth/me"));
  }
  return {
    user,
    can,
    restore,
    login,
    logout,
    dictionaries,
    loadDictionaries,
    communities,
    communityId,
    switching,
    switchCommunity,
    refreshCommunities,
  };
});
