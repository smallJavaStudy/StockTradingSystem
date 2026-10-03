<template>
  <div class="app">
    <nav class="top-nav">
      <span class="nav-brand">股票智能分析系统</span>
      <div class="nav-menu">
        <router-link to="/" exact-active-class="nav-active">股票分析</router-link>
        <router-link to="/market" active-class="nav-active">市场中心</router-link>
        <router-link to="/watchlist" active-class="nav-active">自选股</router-link>
        <router-link to="/topics" active-class="nav-active">题材库</router-link>
        <router-link to="/review" active-class="nav-active">复盘中心</router-link>
        <router-link to="/reports" active-class="nav-active">报告库</router-link>
        <router-link to="/workflow" active-class="nav-active">工作流管理</router-link>
        <router-link to="/chat" active-class="nav-active">智能对话</router-link>
      </div>
    </nav>
    <router-view />
  </div>
</template>

<script setup lang="ts">
</script>
