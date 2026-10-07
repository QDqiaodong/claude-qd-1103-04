import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import IngredientView from '../views/IngredientView.vue'
import RecipeView from '../views/RecipeView.vue'
import MemberView from '../views/MemberView.vue'
import CupOrderView from '../views/CupOrderView.vue'

const routes = [
  { path: '/', redirect: '/ingredient' },
  { path: '/ingredient', name: 'ingredient', component: IngredientView },
  { path: '/recipe', name: 'recipe', component: RecipeView },
  { path: '/member', name: 'member', component: MemberView },
  { path: '/cup-order', name: 'cup-order', component: CupOrderView },
  { path: '/:pathMatch(.*)*', redirect: '/ingredient' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
