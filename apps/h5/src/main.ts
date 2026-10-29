import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import Vant from 'vant';
import 'vant/lib/index.css';
import App from './App.vue';
import './style.css';
const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:page?/:id?', component: { render: () => null } }],
});
createApp(App).use(router).use(Vant).mount('#app');
