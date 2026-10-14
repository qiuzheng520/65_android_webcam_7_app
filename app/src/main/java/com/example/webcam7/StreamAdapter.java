package com.example.webcam7;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频流列表适配器。
 *
 * <p>复用 {@code item_stream.xml}：主页展示【播放】+【收藏】文字按钮，
 * 收藏页展示【播放】【取消收藏】【标签】图标按钮。每种条目显示链接、归属国家与标签。</p>
 */
public class StreamAdapter extends RecyclerView.Adapter<StreamAdapter.ViewHolder> {

    /** 列表事件回调。 */
    public interface Listener {
        /** 点击播放按钮或条目。 */
        void onPlay(String url);

        /** 点击收藏 / 取消收藏按钮。 */
        void onFavorite(String url);

        /** 长按删除单条。 */
        void onDelete(String url);

        /** 点击标签按钮（添加 / 修改标签）。 */
        void onTag(String url);
    }

    private final List<StreamItem> data = new ArrayList<>();
    private final Listener listener;
    private final boolean favoriteMode;

    /**
     * @param favoriteMode true 表示收藏页模式
     * @param listener     事件回调
     */
    public StreamAdapter(boolean favoriteMode, Listener listener) {
        this.favoriteMode = favoriteMode;
        this.listener = listener;
    }

    /**
     * 追加单条（自动去重）。
     *
     * @return true 表示确实新增了一条
     */
    public boolean addItem(String url) {
        if (TextUtils.isEmpty(url) || indexOf(url) >= 0) {
            return false;
        }
        data.add(new StreamItem(url));
        notifyItemInserted(data.size() - 1);
        return true;
    }

    /** 一次性设置收藏数据（带标签）。 */
    public void setFavorites(List<Favorite> favorites) {
        data.clear();
        if (favorites != null) {
            for (Favorite f : favorites) {
                if (!TextUtils.isEmpty(f.url)) {
                    data.add(new StreamItem(f.url, f.tag));
                }
            }
        }
        notifyDataSetChanged();
    }

    /** 移除单条（按内容）。 */
    public void removeItem(String url) {
        int index = indexOf(url);
        if (index >= 0) {
            data.remove(index);
            notifyItemRemoved(index);
        }
    }

    /** 一键清空全部条目。 */
    public void clear() {
        if (data.isEmpty()) {
            return;
        }
        data.clear();
        notifyDataSetChanged();
    }

    /** 更新某条的国家显示。 */
    public void setCountry(String url, String country) {
        int index = indexOf(url);
        if (index >= 0) {
            data.get(index).country = country;
            notifyItemChanged(index);
        }
    }

    /** 更新某条的标签显示。 */
    public void setTag(String url, String tag) {
        int index = indexOf(url);
        if (index >= 0) {
            data.get(index).tag = tag == null ? "" : tag;
            notifyItemChanged(index);
        }
    }

    private int indexOf(String url) {
        for (int i = 0; i < data.size(); i++) {
            if (TextUtils.equals(data.get(i).url, url)) {
                return i;
            }
        }
        return -1;
    }

    /** 当前条目数量。 */
    public int getItemCount() {
        return data.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_stream, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final StreamItem item = data.get(position);
        final String url = item.url;

        holder.tvUrl.setText(url);

        // 国家
        if (item.country == null) {
            holder.tvCountry.setText(holder.itemView.getContext()
                    .getString(R.string.country_querying));
        } else {
            holder.tvCountry.setText(holder.itemView.getContext()
                    .getString(R.string.country_label, item.country));
        }

        // 标签（仅收藏页且有标签时显示）
        if (favoriteMode && !TextUtils.isEmpty(item.tag)) {
            holder.tvTag.setVisibility(View.VISIBLE);
            holder.tvTag.setText(holder.itemView.getContext()
                    .getString(R.string.tag_display, item.tag));
        } else {
            holder.tvTag.setVisibility(View.GONE);
        }

        holder.btnFavorite.setText(
                favoriteMode ? holder.itemView.getContext().getString(R.string.btn_unfavorite)
                        : holder.itemView.getContext().getString(R.string.btn_favorite));

        // 收藏页显示图标按钮，主页显示文字按钮
        int textVis = favoriteMode ? View.GONE : View.VISIBLE;
        int iconVis = favoriteMode ? View.VISIBLE : View.GONE;
        holder.btnFavorite.setVisibility(textVis);
        holder.btnPlay.setVisibility(textVis);
        holder.btnPlayIcon.setVisibility(iconVis);
        holder.btnUnfavoriteIcon.setVisibility(iconVis);
        holder.btnTagIcon.setVisibility(iconVis);

        holder.btnPlay.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlay(url);
            }
        });
        holder.btnFavorite.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFavorite(url);
            }
        });
        // 图标按钮：播放
        holder.btnPlayIcon.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlay(url);
            }
        });
        // 图标按钮：取消收藏
        holder.btnUnfavoriteIcon.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFavorite(url);
            }
        });
        // 图标按钮：标签
        holder.btnTagIcon.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTag(url);
            }
        });

        // 点击条目直接播放
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlay(url);
            }
        });
        // 长按删除单条
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onDelete(url);
                return true;
            }
            return false;
        });
    }

    /** 列表项视图。 */
    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvUrl;
        final TextView tvCountry;
        final TextView tvTag;
        final Button btnPlay;
        final Button btnFavorite;
        final ImageButton btnPlayIcon;
        final ImageButton btnUnfavoriteIcon;
        final ImageButton btnTagIcon;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUrl = itemView.findViewById(R.id.tvUrl);
            tvCountry = itemView.findViewById(R.id.tvCountry);
            tvTag = itemView.findViewById(R.id.tvTag);
            btnPlay = itemView.findViewById(R.id.btnPlay);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            btnPlayIcon = itemView.findViewById(R.id.btnPlayIcon);
            btnUnfavoriteIcon = itemView.findViewById(R.id.btnUnfavoriteIcon);
            btnTagIcon = itemView.findViewById(R.id.btnTagIcon);
        }
    }
}
