<template>
  <div class="home">
    <div class="content glass">
      <h1 class="title float">AI 应用中心</h1>
      <div class="buttons">
        <router-link to="/love">
          <button class="btn glow">
            <span class="icon">💕</span>
            AI 恋爱大师
          </button>
        </router-link>
        <router-link to="/agent">
          <button class="btn glow">
            <span class="icon">🤖</span>
            AI 超级智能体
          </button>
        </router-link>
      </div>
    </div>
    <div class="decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  padding: var(--padding-desktop);
}

.content {
  width: 100%;
  max-width: var(--max-width-desktop);
  padding: 40px;
  text-align: center;
  position: relative;
  z-index: 1;
}

.title {
  font-size: clamp(2rem, 5vw, 3rem);
  margin-bottom: 40px;
  background: linear-gradient(45deg, #ff6b6b, #ff69b4);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
}

.buttons {
  display: flex;
  gap: clamp(10px, 3vw, 20px);
  justify-content: center;
  flex-wrap: wrap;
  padding: 0 clamp(10px, 3vw, 20px);
}

.btn {
  padding: clamp(12px, 2vw, 15px) clamp(20px, 4vw, 30px);
  font-size: clamp(1rem, 2vw, 1.2rem);
  border: none;
  border-radius: 25px;
  background: linear-gradient(45deg, var(--primary-color), var(--secondary-color));
  color: white;
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
}

.btn:hover {
  transform: translateY(-5px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.2);
}

.icon {
  font-size: 1.4em;
}

/* 装饰性圆圈 */
.decoration {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  opacity: 0.1;
}

.circle-1 {
  width: 300px;
  height: 300px;
  background: var(--primary-color);
  top: -150px;
  right: -150px;
}

.circle-2 {
  width: 200px;
  height: 200px;
  background: var(--secondary-color);
  bottom: -100px;
  left: -100px;
}

.circle-3 {
  width: 150px;
  height: 150px;
  background: linear-gradient(45deg, var(--primary-color), var(--secondary-color));
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

/* 平板适配 */
@media (max-width: 1024px) {
  .home {
    padding: var(--padding-tablet);
  }
  
  .content {
    padding: 30px;
  }
  
  .circle-1 {
    width: 250px;
    height: 250px;
  }
  
  .circle-2 {
    width: 180px;
    height: 180px;
  }
}

/* 手机适配 */
@media (max-width: 768px) {
  .home {
    padding: var(--padding-mobile);
  }
  
  .content {
    padding: 20px;
  }
  
  .buttons {
    flex-direction: column;
    align-items: center;
  }
  
  .btn {
    width: 100%;
    max-width: 300px;
    justify-content: center;
  }
  
  .circle-1 {
    width: 200px;
    height: 200px;
  }
  
  .circle-2 {
    width: 150px;
    height: 150px;
  }
  
  .circle-3 {
    width: 120px;
    height: 120px;
  }
}

/* 横屏模式适配 */
@media (orientation: landscape) and (max-height: 600px) {
  .home {
    padding: 20px;
  }
  
  .content {
    padding: 20px;
  }
  
  .title {
    margin-bottom: 20px;
  }
  
  .buttons {
    flex-direction: row;
  }
}
</style> 