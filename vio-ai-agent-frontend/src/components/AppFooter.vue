<template>
  <footer class="app-footer">
    <p class="footer-text">VIO AI Agent © 2026</p>
    <p class="footer-links">
      <a href="#">用户协议</a>
      <span>·</span>
      <a href="#">隐私政策</a>
    </p>
  </footer>
</template>

<style scoped>
.app-footer {
  text-align: center;
  padding: 30px 20px;
  opacity: 0.4;
  font-size: 13px;
}

.footer-text {
  margin-bottom: 6px;
  color: rgba(255, 255, 255, 0.5);
}

.footer-links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.footer-links a {
  color: rgba(255, 255, 255, 0.4);
  transition: color 0.2s;
}

.footer-links a:hover {
  color: rgba(255, 255, 255, 0.7);
}

.footer-links span {
  color: rgba(255, 255, 255, 0.2);
}
</style>