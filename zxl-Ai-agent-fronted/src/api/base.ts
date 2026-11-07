// API 基础路径
// 本地开发：使用相对路径 /api（由 vite proxy 代理到本地后端）
// 生产部署：通过环境变量 VITE_API_BASE 指向后端完整 URL（如 https://your-backend.onrender.com/api）
export const API_BASE = import.meta.env.VITE_API_BASE || '/api'
