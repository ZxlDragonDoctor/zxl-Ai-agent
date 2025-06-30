<template>
  <div class="app-container">
    <div class="stars" ref="starsContainer"></div>
    <div class="shooting-stars"></div>
    <router-view></router-view>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const starsContainer = ref(null)
let animationFrame = null

// 创建星星
function createStars() {
  const starsContainer = document.querySelector('.stars')
  const starCount = 200

  for (let i = 0; i < starCount; i++) {
    const star = document.createElement('div')
    star.className = 'star'
    star.style.width = Math.random() * 2 + 'px'
    star.style.height = star.style.width
    star.style.left = Math.random() * 100 + '%'
    star.style.top = Math.random() * 100 + '%'
    starsContainer.appendChild(star)
  }
}

// 创建流星
function createShootingStar() {
  const container = document.querySelector('.shooting-stars')
  const star = document.createElement('div')
  
  // 随机大小类名
  const sizes = ['small', 'medium', 'large']
  const sizeClass = sizes[Math.floor(Math.random() * sizes.length)]
  star.className = `shooting-star ${sizeClass}`
  
  // 随机起始位置（右侧区域）
  const startX = 50 + Math.random() * 50 // 50-100%
  const startY = Math.random() * 50 // 0-50%
  
  // 设置流星长度
  const length = 100 + Math.random() * 150 // 100-250px
  star.style.width = `${length}px`
  
  // 设置位置
  star.style.left = `${startX}%`
  star.style.top = `${startY}%`
  
  container.appendChild(star)
  
  // 动画结束后移除
  star.addEventListener('animationend', () => {
    star.remove()
  })
}

// 持续创建流星
function startShootingStars() {
  // 初始化创建多个流星
  for (let i = 0; i < 5; i++) {
    setTimeout(() => createShootingStar(), i * 200)
  }
  
  // 持续创建新流星
  return setInterval(() => {
    const count = Math.floor(Math.random() * 3) + 1 // 随机创建1-3个流星
    for (let i = 0; i < count; i++) {
      setTimeout(() => createShootingStar(), i * 200)
    }
  }, 1000)
}

let shootingStarsInterval

onMounted(() => {
  createStars()
  shootingStarsInterval = startShootingStars()
})

onUnmounted(() => {
  if (shootingStarsInterval) {
    clearInterval(shootingStarsInterval)
  }
})
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 30px;
  position: relative;
}

@media (max-width: 768px) {
  .app-container {
    padding: 0 15px;
  }
}
</style>
